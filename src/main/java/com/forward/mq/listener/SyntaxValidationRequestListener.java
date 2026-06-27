package com.forward.mq.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forward.mq.MQConfig;
import com.forward.model.SyntaxValidationResponse;
import com.forward.s3.S3FileDownloader;
import com.forward.validator.SyntaxValidator;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens on {@code SYNTAX.VALIDATION.REQUEST.QUEUE}.
 *
 * For each incoming message:
 *  1. Parses the JSON body into a {@link com.forward.model.SyntaxValidationRequest}
 *  2. Downloads the payment XML file from S3 (or LocalStack) via {@link S3FileDownloader}
 *  3. Validates the XML bytes against the pain.008.001.08 XSD via {@link SyntaxValidator}
 *  4. Writes a {@link SyntaxValidationResponse} JSON to {@code SYNTAX.VALIDATION.RESPONSE.QUEUE}
 *     with the same {@code JMSCorrelationID} so the Camunda workflow service can correlate it back.
 *
 * The input message JSON must contain the key {@code "paymentFilePath"} which is the
 * full S3 URI (e.g. {@code s3://fwb-bucket/payments/file.xml}).
 * This matches what {@code SyntaxValidationRequestTaskDefinition} sends from the workflow service.
 *
 * Consumer and producer run on separate JMS connections to avoid shared TCP-state issues.
 */
public class SyntaxValidationRequestListener implements MessageListener {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Poison-message guard ──────────────────────────────────────────────────
    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final MQConfig        mqConfig;
    private final S3FileDownloader s3FileDownloader;
    private final SyntaxValidator  syntaxValidator;

    // Two isolated connections — producer activity cannot affect consumer acknowledgment
    private Connection      consumerConnection;
    private Connection      producerConnection;
    private Session         consumerSession;
    private Session         producerSession;
    private MessageConsumer consumer;
    private MessageProducer producer;

    public SyntaxValidationRequestListener(MQConfig mqConfig,
                                           S3FileDownloader s3FileDownloader,
                                           SyntaxValidator syntaxValidator) {
        this.mqConfig         = mqConfig;
        this.s3FileDownloader = s3FileDownloader;
        this.syntaxValidator  = syntaxValidator;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        try {
            MQConnectionFactory factory = createFactory();

            // Consumer — AUTO_ACKNOWLEDGE: MQ acks automatically when onMessage() returns normally
            consumerConnection = factory.createConnection();
            consumerSession    = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue requestQueue = consumerSession.createQueue(MQConfig.REQUEST_QUEUE);
            consumer           = consumerSession.createConsumer(requestQueue);
            consumer.setMessageListener(this);

            // Producer — completely isolated from the consumer
            producerConnection = factory.createConnection();
            producerSession    = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue responseQueue = producerSession.createQueue(MQConfig.RESPONSE_QUEUE);
            producer            = producerSession.createProducer(responseQueue);

            // Start producer first, then consumer (ensures responses can be sent before messages arrive)
            producerConnection.start();
            consumerConnection.start();

            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  SyntaxValidationRequestListener STARTED     ║");
            System.out.println("╠══════════════════════════════════════════════╣");
            System.out.println("║  Listening on  : " + MQConfig.REQUEST_QUEUE);
            System.out.println("║  Responding to : " + MQConfig.RESPONSE_QUEUE);
            System.out.println("╚══════════════════════════════════════════════╝");

        } catch (JMSException e) {
            throw new RuntimeException("Failed to start SyntaxValidationRequestListener", e);
        }
    }

    public void stop() {
        closeQuietly(consumer,           "consumer");
        closeQuietly(producer,           "producer");
        closeQuietly(consumerSession,    "consumerSession");
        closeQuietly(producerSession,    "producerSession");
        closeQuietly(consumerConnection, "consumerConnection");
        closeQuietly(producerConnection, "producerConnection");
        System.out.println("✓ SyntaxValidationRequestListener stopped");
    }

    // ── Message handling ──────────────────────────────────────────────────────

    @Override
    public void onMessage(Message message) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SyntaxValidationRequestListener: message received");

        String correlationId = null;
        boolean responseSent = false;

        try {
            // ── Poison-message guard ──────────────────────────────────────────
            int deliveryCount = message.getIntProperty("JMSXDeliveryCount");
            if (deliveryCount > MAX_DELIVERY_ATTEMPTS) {
                System.err.println("✗ POISON MESSAGE — discarding after "
                        + deliveryCount + " delivery attempts. JMSMessageID="
                        + message.getJMSMessageID());
                return;
            }

            if (!(message instanceof TextMessage textMessage)) {
                System.err.println("✗ Unsupported message type: "
                        + message.getClass().getSimpleName());
                return;
            }

            correlationId = message.getJMSCorrelationID();
            String requestBody = textMessage.getText();

            System.out.println("  JMSMessageID    : " + message.getJMSMessageID());
            System.out.println("  Correlation ID  : " + correlationId);
            System.out.println("  Request Payload : " + requestBody);

            // ── Step 1: parse request ─────────────────────────────────────────
            // The workflow service sends: {"paymentFilePath": "s3://bucket/path/file.xml"}
            Map<?, ?> requestMap = OBJECT_MAPPER.readValue(requestBody, Map.class);
            String s3Uri = (String) requestMap.get("paymentFilePath");

            if (s3Uri == null || s3Uri.isBlank()) {
                System.err.println("✗ Missing 'paymentFilePath' in request body");
                sendResponse(correlationId, SyntaxValidationResponse.invalid(
                        "SVE_001", "Missing 'paymentFilePath' in request"));
                responseSent = true;
                return;
            }

            System.out.println("  S3 URI          : " + s3Uri);

            // ── Step 2: download XML from S3 / LocalStack ─────────────────────
            byte[] xmlBytes;
            try {
                long s3Start = System.nanoTime();
                xmlBytes = s3FileDownloader.download(s3Uri);
                long s3ElapsedMs = (System.nanoTime() - s3Start) / 1_000_000;
                System.out.println("  S3 download time: " + s3ElapsedMs + " ms");
            } catch (S3FileDownloader.S3DownloadException e) {
                System.err.println("✗ S3 download failed: " + e.getMessage());
                sendResponse(correlationId, SyntaxValidationResponse.invalid(
                        "SVE_004", "S3 download failed: " + e.getMessage()));
                responseSent = true;
                return;
            }

            // ── Step 3: validate XML against pain.008.001.08 XSD ─────────────
            long xsdStart = System.nanoTime();
            SyntaxValidationResponse validationResult = syntaxValidator.validate(xmlBytes);
            long xsdElapsedMs = (System.nanoTime() - xsdStart) / 1_000_000;
            System.out.println("  XSD validation time: " + xsdElapsedMs + " ms");
            System.out.println("  Validation result: " + validationResult);

            // ── Step 4: send response to response queue ────────────────────────
            sendResponse(correlationId, validationResult);
            responseSent = true;

        } catch (Throwable t) {
            System.err.println("!!! CRITICAL FAILURE in SyntaxValidationRequestListener: "
                    + t.getMessage());
            t.printStackTrace();
        } finally {
            // Fallback — always send a response so the Camunda process is never left hanging
            if (!responseSent && correlationId != null) {
                trySendErrorResponse(correlationId, "SVE_INTERNAL_ERROR",
                        "Unexpected error during processing");
            }
            System.out.println("=".repeat(80));
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────────

    private void sendResponse(String correlationId,
                              SyntaxValidationResponse response) throws Exception {
        // Build the response JSON — use a LinkedHashMap to get predictable key order in logs
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status",       response.getStatus());
        payload.put("errorCode",    response.getErrorCode() != null ? response.getErrorCode() : "");
        payload.put("errorMessage", response.getErrorMessage() != null ? response.getErrorMessage() : "");

        String payloadJson = OBJECT_MAPPER.writeValueAsString(payload);

        TextMessage responseMessage = producerSession.createTextMessage(payloadJson);
        responseMessage.setJMSCorrelationID(correlationId);
        producer.send(responseMessage);

        System.out.println("  ✓ Response sent to " + MQConfig.RESPONSE_QUEUE);
        System.out.println("    Payload        : " + payloadJson);
        System.out.println("    Correlation ID : " + correlationId);
    }

    private void trySendErrorResponse(String correlationId,
                                      String errorCode,
                                      String errorMessage) {
        try {
            sendResponse(correlationId,
                    SyntaxValidationResponse.invalid(errorCode, errorMessage));
        } catch (Exception e) {
            System.err.println("✗ Failed to send error response: " + e.getMessage());
        }
    }

    // ── MQ factory ────────────────────────────────────────────────────────────

    private MQConnectionFactory createFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(mqConfig.getHost());
        factory.setPort(mqConfig.getPort());
        factory.setChannel(mqConfig.getChannel());
        factory.setQueueManager(mqConfig.getQueueManager());
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        return factory;
    }

    // ── Close helpers ─────────────────────────────────────────────────────────

    private void closeQuietly(MessageConsumer c, String name) {
        if (c != null) try { c.close(); } catch (JMSException e) { warn(name, e); }
    }

    private void closeQuietly(MessageProducer p, String name) {
        if (p != null) try { p.close(); } catch (JMSException e) { warn(name, e); }
    }

    private void closeQuietly(Session s, String name) {
        if (s != null) try { s.close(); } catch (JMSException e) { warn(name, e); }
    }

    private void closeQuietly(Connection c, String name) {
        if (c != null) try { c.close(); } catch (JMSException e) { warn(name, e); }
    }

    private void warn(String resource, JMSException e) {
        System.err.println("WARN: Failed to close " + resource + ": " + e.getMessage());
    }
}
