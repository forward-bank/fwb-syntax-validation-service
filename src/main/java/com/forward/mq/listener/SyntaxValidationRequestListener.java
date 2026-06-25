package com.forward.mq.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forward.mq.MQConfig;
import com.forward.model.SyntaxValidationResponse;
import com.forward.validator.SyntaxValidator;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.*;
import java.util.Map;

public class SyntaxValidationRequestListener implements MessageListener {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MQConfig        mqConfig;
    private final SyntaxValidator validator;

    // ── Two fully isolated connections ────────────────────────────────────────
    // Consumer and producer on separate connections means zero shared TCP state.
    // Producer activity can never disturb consumer session acknowledgment.
    private Connection      consumerConnection;
    private Connection      producerConnection;

    private Session         consumerSession;
    private Session         producerSession;

    private MessageConsumer consumer;
    private MessageProducer producer;

    public SyntaxValidationRequestListener(MQConfig mqConfig) {
        this.mqConfig  = mqConfig;
        this.validator = new SyntaxValidator();
    }

    public void start() {
        try {
            MQConnectionFactory factory = createFactory();

            // ── Consumer connection — AUTO_ACKNOWLEDGE ────────────────────────
            // No manual acknowledge() calls needed. MQ acknowledges automatically
            // when onMessage() returns normally. Clean and safe.
            consumerConnection = factory.createConnection();
            consumerSession    = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue requestQueue = consumerSession.createQueue(MQConfig.REQUEST_QUEUE);
            consumer           = consumerSession.createConsumer(requestQueue);
            consumer.setMessageListener(this);

            // ── Producer connection — completely isolated ──────────────────────
            producerConnection = factory.createConnection();
            producerSession    = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue responseQueue = producerSession.createQueue(MQConfig.RESPONSE_QUEUE);
            producer            = producerSession.createProducer(responseQueue);

            // Start consumer LAST — only after producer is fully ready
            producerConnection.start();
            consumerConnection.start();

            System.out.println("✓ Connected to IBM MQ");
            System.out.println("✓ Listening on  : " + MQConfig.REQUEST_QUEUE);
            System.out.println("✓ Responding to : " + MQConfig.RESPONSE_QUEUE);

        } catch (JMSException e) {
            throw new RuntimeException("Failed to start SyntaxValidationRequestListener", e);
        }
    }

    @Override
    public void onMessage(Message message) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SyntaxValidationRequestListener: message received");

        String correlationId = null;
        boolean responseSent = false;

        try {
            int deliveryCount = message.getIntProperty("JMSXDeliveryCount");

            System.out.println("  Thread          : " + Thread.currentThread().getName());
            System.out.println("  JMSMessageID    : " + message.getJMSMessageID());
            System.out.println("  JMSRedelivered  : " + message.getJMSRedelivered());
            System.out.println("  JMSDeliveryCount: " + deliveryCount);

            // ── Poison message guard ──────────────────────────────────────────
            if (deliveryCount > 5) {
                System.err.println("✗ POISON MESSAGE — discarding after " + deliveryCount + " attempts");
                return;
            }

            if (!(message instanceof TextMessage)) {
                System.err.println("✗ Unsupported message type: " + message.getClass().getSimpleName());
                return;
            }

            //correlationId      = message.getJMSMessageID();
            correlationId = message.getJMSCorrelationID();
            String requestBody = ((TextMessage) message).getText();

            System.out.println("  Correlation ID  : " + correlationId);
            System.out.println("  Request Payload : " + requestBody);

            Map<String, Object> requestMap = OBJECT_MAPPER.readValue(requestBody, Map.class);
            String paymentFilePath          = (String) requestMap.get("paymentFilePath");

            SyntaxValidationResponse response = validator.validate(paymentFilePath);
            System.out.println("  Validation Result : " + response);

            System.out.println("  → Attempting sendResponse...");
            sendResponse(correlationId, response);
            System.out.println("  ✓ sendResponse completed");
            responseSent = true;

        } catch (Throwable t) {
            System.err.println("!!! CRITICAL FAILURE during message processing: " + t.getMessage());
            t.printStackTrace();

        } finally {
            if (!responseSent && correlationId != null) {
                trySendErrorResponse(correlationId);
            }
            // AUTO_ACKNOWLEDGE: no manual acknowledge() needed.
            // MQ handles it automatically when onMessage() exits.
            System.out.println("=".repeat(80));
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

    // ── Send helpers ──────────────────────────────────────────────────────────

    private void sendResponse(String correlationId,
                              SyntaxValidationResponse response) throws Exception {
        String payload = OBJECT_MAPPER.writeValueAsString(
                Map.of(
                        "status",    response.getStatus(),
                        "errorCode", response.getErrorCode() != null ? response.getErrorCode() : ""
                )
        );

        TextMessage responseMessage = producerSession.createTextMessage(payload);
        responseMessage.setJMSCorrelationID(correlationId);
        producer.send(responseMessage);

        System.out.println("  ✓ Response sent to " + MQConfig.RESPONSE_QUEUE);
        System.out.println("    Payload        : " + payload);
        System.out.println("    Correlation ID : " + correlationId);
    }

    private void trySendErrorResponse(String correlationId) {
        try {
            sendResponse(correlationId, SyntaxValidationResponse.invalid("SVE_INTERNAL_ERROR"));
        } catch (Exception e) {
            System.err.println("✗ Failed to send error response: " + e.getMessage());
        }
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

    private MQConnectionFactory createFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(mqConfig.getHost());
        factory.setPort(mqConfig.getPort());
        factory.setChannel(mqConfig.getChannel());
        factory.setQueueManager(mqConfig.getQueueManager());
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        return factory;
    }

    private void warn(String resource, JMSException e) {
        System.err.println("WARN: Failed to close " + resource + ": " + e.getMessage());
    }
}