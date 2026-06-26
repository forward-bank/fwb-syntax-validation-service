# FWB Syntax Validation Service

A Spring Boot microservice that validates SEPA Direct Debit **pain.008.001.08** payment XML files against the ISO 20022 XSD schema. It sits between the Camunda-based Direct Debit Workflow Service and the downstream processing pipeline, acting as a pure message-driven worker: it consumes validation requests from an IBM MQ queue, downloads the payment file from S3, runs XSD validation, and writes the result back to a response queue.

---

## Table of Contents

1. [How It Fits in the System](#how-it-fits-in-the-system)
2. [End-to-End Flow Diagram](#end-to-end-flow-diagram)
3. [Processing Flow Detail](#processing-flow-detail)
4. [Project Structure](#project-structure)
5. [IBM MQ — Queues and Message Contracts](#ibm-mq--queues-and-message-contracts)
6. [S3 / LocalStack Integration](#s3--localstack-integration)
7. [XSD Validation](#xsd-validation)
8. [Error Codes](#error-codes)
9. [Spring Configuration](#spring-configuration)
10. [application.properties Reference](#applicationproperties-reference)
11. [Running Locally](#running-locally)
12. [LocalStack Setup](#localstack-setup)

---

## How It Fits in the System

```
fwb-direct-debit-workflow-service  (Camunda BPMN engine)
        │
        │  syntax_validation_request_task (Service Task)
        │  sends JSON to ──────────────────────────────────────────────────────┐
        │                                                                       │
        │                                                          SYNTAX.VALIDATION
        │                                                          .REQUEST.QUEUE
        │                                                               │
        │                                              fwb-syntax-validation-service
        │                                              SyntaxValidationRequestListener
        │                                                  1. parse request
        │                                                  2. download XML from S3
        │                                                  3. validate against XSD
        │                                                  4. write result ──────┐
        │                                                                        │
        │                                                          SYNTAX.VALIDATION
        │                                                          .RESPONSE.QUEUE
        │                                                               │
        │  syntax_validation_response_task (Receive Task)              │
        │  SyntaxValidationResponseListener ◄──────────────────────────┘
        │  correlates message back to waiting Camunda process instance
        │
        ▼
  [is_syntax_valid?] gateway  →  duplicate_check_task  →  ...
```

---

## End-to-End Flow Diagram

```
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  fwb-direct-debit-workflow-service                                        │
  │                                                                           │
  │  SyntaxValidationRequestTaskDefinition.execute()                          │
  │    paymentFilePath = TRIGGER_MESSAGE.fileS3Path                           │
  │    payload = { "paymentFilePath": "FWB_DIRECT_DEBIT/.../file.xml" }       │
  │    JMSCorrelationID = jmsMessageId (process variable)                     │
  └───────────────────────────────┬──────────────────────────────────────────┘
                                  │ TextMessage → SYNTAX.VALIDATION.REQUEST.QUEUE
                                  ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  fwb-syntax-validation-service                                            │
  │                                                                           │
  │  SyntaxValidationRequestListener.onMessage()                              │
  │    │                                                                      │
  │    ├─ [1] Parse JSON body → extract paymentFilePath                       │
  │    │                                                                      │
  │    ├─ [2] S3FileDownloader.download(paymentFilePath)                      │
  │    │       bucket : fwb-payments-dev  (from aws.s3.bucket)                │
  │    │       key    : FWB_DIRECT_DEBIT/.../file.xml                         │
  │    │       →  byte[] xmlBytes                                             │
  │    │                                                                      │
  │    ├─ [3] SyntaxValidator.validate(xmlBytes)                              │
  │    │       loads XSD once: /xsd/pain008/pain_008_001_08.xsd               │
  │    │       creates fresh javax.xml.validation.Validator per call          │
  │    │       collects ALL violations (does not stop at first error)         │
  │    │       →  SyntaxValidationResponse { status, errorCode, errorMessage }│
  │    │                                                                      │
  │    └─ [4] sendResponse(correlationId, response)                           │
  │            payload = { "status":"VALID"|"INVALID",                        │
  │                        "errorCode":"...", "errorMessage":"..." }          │
  │            JMSCorrelationID = same correlationId as request               │
  └───────────────────────────────┬──────────────────────────────────────────┘
                                  │ TextMessage → SYNTAX.VALIDATION.RESPONSE.QUEUE
                                  ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  fwb-direct-debit-workflow-service                                        │
  │                                                                           │
  │  SyntaxValidationResponseListener.onMessage()                             │
  │    correlates message back to Camunda process instance by correlationId   │
  │    sets process variable: is_syntax_valid = true | false                  │
  │                                                                           │
  │  [is_syntax_valid?] gateway                                               │
  │    true  →  duplicate_check_task                                          │
  │    false →  End (Invalid Syntax)                                          │
  └──────────────────────────────────────────────────────────────────────────┘
```

---

## Processing Flow Detail

### Step 1 — Parse the request message

`SyntaxValidationRequestListener.onMessage()` reads the JMS `TextMessage` body as JSON:

```json
{ "paymentFilePath": "FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/I1234567890123.FWB.pain00800108.ABCD123.PM.pgp_12345.145.xml" }
```

The field `paymentFilePath` is a bucket-relative S3 object key — no `s3://` prefix. The service reads `JMSCorrelationID` from the incoming message and passes it through unchanged to the response, so the workflow service can match the reply to the correct waiting process instance.

**Poison message guard** — if `JMSXDeliveryCount > 5` the message is discarded with a log warning to prevent infinite redelivery loops.

---

### Step 2 — Download the XML from S3

`S3FileDownloader.download(paymentFilePath)` builds a `GetObjectRequest`:

```
bucket : fwb-payments-dev        (aws.s3.bucket property)
key    : FWB_DIRECT_DEBIT/PAYMENT_FILES/.../file.xml   (paymentFilePath as-is)
```

Returns raw `byte[]`. Throws `S3DownloadException` (mapped to error code `SVE_004`) if the object is not found or any AWS SDK error occurs.

For local development the S3 client is pointed at **LocalStack** (`http://localhost:4566`) with path-style access enabled. See [LocalStack Setup](#localstack-setup).

---

### Step 3 — Validate against pain.008.001.08 XSD

`SyntaxValidator.validate(byte[])` runs JAXP XSD validation:

- The XSD (`/xsd/pain008/pain_008_001_08.xsd`) is compiled **once** at bean construction into a `javax.xml.validation.Schema`. This is expensive and thread-safe — it is reused across all messages.
- A fresh `javax.xml.validation.Validator` is created **per call** because `Validator` is not thread-safe.
- A custom `ErrorHandler` accumulates all schema violations instead of stopping at the first. The full list is joined and included in `errorMessage` so the calling service has complete diagnostic information.

---

### Step 4 — Write the response

A JSON `TextMessage` is sent to `SYNTAX.VALIDATION.RESPONSE.QUEUE` with `JMSCorrelationID` set to the same value that arrived on the request. This is what the Camunda workflow service matches on when it calls `runtimeService.createMessageCorrelation("syntax_validation_response_message").processInstanceVariableEquals("correlationId", correlationId)`.

**Fallback guarantee** — the `finally` block in `onMessage()` always sends a response if one has not been sent yet. This ensures the Camunda process instance is never left hanging in a Receive Task indefinitely.

Consumer and producer use **two separate JMS connections** so producer activity (sending responses) cannot interfere with consumer session acknowledgment.

---

## Project Structure

```
src/main/java/com/forward/
│
├── SyntaxValidationApplication.java          # @SpringBootApplication entry point
│
├── config/
│   ├── S3Config.java                         # S3Client bean (LocalStack or real AWS)
│   └── MQListenerConfig.java                 # Wires MQ listener with init/destroy lifecycle
│
├── mq/
│   ├── MQConfig.java                         # Connection POJO + queue name constants
│   └── listener/
│       └── SyntaxValidationRequestListener.java  # Core message handler (steps 1–4)
│
├── s3/
│   └── S3FileDownloader.java                 # Downloads XML bytes from S3 by object key
│
├── validator/
│   └── SyntaxValidator.java                  # JAXP XSD validation against pain.008.001.08
│
└── model/
    ├── SyntaxValidationRequest.java          # Request model (paymentFilePath)
    └── SyntaxValidationResponse.java         # Response model (status, errorCode, errorMessage)

src/main/resources/
├── application.properties                    # All runtime configuration
└── xsd/
    └── pain008/
        └── pain_008_001_08.xsd               # SEPA Direct Debit pain.008.001.08 schema
```

---

## IBM MQ — Queues and Message Contracts

### Queues

| Queue | Direction | Purpose |
|-------|-----------|---------|
| `SYNTAX.VALIDATION.REQUEST.QUEUE` | Inbound | Receives validation requests from the workflow service |
| `SYNTAX.VALIDATION.RESPONSE.QUEUE` | Outbound | Sends validation results back to the workflow service |

### Request Message

Sent by `SyntaxValidationRequestTaskDefinition` in `fwb-direct-debit-workflow-service`.

- **Type:** `TextMessage`
- **JMSCorrelationID:** set to the `jmsMessageId` process variable (the original IBM MQ message ID that started the Camunda process instance)
- **Body (JSON):**

```json
{
  "paymentFilePath": "FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/I1234567890123.FWB.pain00800108.ABCD123.PM.pgp_12345.145.xml"
}
```

`paymentFilePath` is a bucket-relative S3 key. No `s3://` prefix is included — the service prepends the configured bucket name itself.

### Response Message

- **Type:** `TextMessage`
- **JMSCorrelationID:** echoed back unchanged from the request
- **Body (JSON):**

```json
// Success
{
  "status": "VALID",
  "errorCode": "",
  "errorMessage": ""
}

// Failure
{
  "status": "INVALID",
  "errorCode": "SVE_002",
  "errorMessage": "line 14: cvc-complex-type.2.4.a: Invalid content was found starting with element 'BIC'. One of '{AnyURI}' is expected.; line 28: ..."
}
```

The `JMSCorrelationID` on the response is what `SyntaxValidationResponseListener` in the workflow service uses to correlate back to the waiting Camunda Receive Task (`syntax_validation_response_task`).

---

## S3 / LocalStack Integration

### How the bucket and key are resolved

```
Property         : aws.s3.bucket = fwb-payments-dev
Incoming field   : paymentFilePath = FWB_DIRECT_DEBIT/PAYMENT_FILES/.../file.xml
                                     └─── used directly as the S3 object key ───┘

Effective request: GET s3://fwb-payments-dev/FWB_DIRECT_DEBIT/PAYMENT_FILES/.../file.xml
```

A leading `/` on `paymentFilePath` is stripped automatically — S3 keys must not begin with `/`.

### LocalStack (local development)

When `aws.localstack.enabled=true`, `S3Config` builds an `S3Client` that:
- Points at `http://localhost:4566`
- Uses path-style access (`pathStyleAccessEnabled=true`) — required because LocalStack does not resolve virtual-hosted subdomains like `fwb-payments-dev.localhost`
- Uses static credentials (`test` / `test`) — LocalStack accepts any non-blank values

### Production

Set `aws.localstack.enabled=false`. The `S3Client` is built with `DefaultCredentialsProvider`, which picks up credentials from the standard AWS chain: environment variables → `~/.aws/credentials` → EC2/ECS instance profile.

---

## XSD Validation

The schema file is `src/main/resources/xsd/pain008/pain_008_001_08.xsd` — the ISO 20022 SEPA Direct Debit Origination (DDO) pain.008.001.08 schema published by SWIFT/Forward Bank.

`SyntaxValidator` uses the standard Java JAXP API (`javax.xml.validation`) — no third-party XML libraries required:

```
SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
    .newSchema(StreamSource)          ← compiled once at startup, thread-safe
    .newValidator()                   ← created per call, NOT thread-safe
    .validate(StreamSource)           ← validates the downloaded XML bytes
```

The custom `ErrorHandler` does **not** throw on the first error. Instead it accumulates all `SAXParseException` errors and returns them all in `errorMessage`, joined by `;`. Only `fatalError` (e.g. completely malformed XML) aborts early.

### Validation error detail example

```
SVE_002 | line 14: cvc-complex-type.2.4.a: Invalid content was found starting with element 'BIC'. One of '{AnyURI}' is expected.;
          line 22: cvc-minLength-valid: Value '' with length = '0' is not facet-valid with respect to minLength '1' for type 'Max35Text'.
```

---

## Error Codes

| Code | Meaning | Triggered by |
|------|---------|-------------|
| `SVE_001` | Missing or blank `paymentFilePath` in request, or empty XML bytes downloaded | Listener validation / `SyntaxValidator` |
| `SVE_002` | XSD schema validation failed | `SyntaxValidator` |
| `SVE_003` | IO error reading XML bytes | `SyntaxValidator` |
| `SVE_004` | S3 download failed (object not found, access denied, network error) | `S3FileDownloader` |
| `SVE_INTERNAL_ERROR` | Unexpected runtime exception not covered by the above | `onMessage()` finally block |

---

## Spring Configuration

### S3Config

```
@Configuration
S3Config
  @Bean S3Client s3Client()
    if aws.localstack.enabled → LocalStack endpoint, path-style, static credentials
    else                      → DefaultCredentialsProvider (production AWS chain)
```

### MQListenerConfig

```
@Configuration
MQListenerConfig
  @Bean MQConfig mqConfig()
    → POJO holding host/port/channel/queueManager

  @Bean(initMethod="start", destroyMethod="stop")
  SyntaxValidationRequestListener
    → Spring calls start()  after context is fully wired
    → Spring calls stop()   during graceful shutdown
```

### SyntaxValidator

`@Component` — Spring creates a single instance. The XSD is compiled in the constructor. This means the application will **fail to start** (with `IllegalStateException`) if the XSD file is missing from the classpath, which is the desired behaviour — a misconfigured deployment fails fast rather than silently.

### S3FileDownloader

`@Component` with `@Value("${aws.s3.bucket:fwb-payments-dev}")` — the bucket name is injected from `application.properties`. The default value `fwb-payments-dev` is the fallback if the property is absent.

---

## application.properties Reference

```properties
# ─── Server ────────────────────────────────────────────────────────────────
server.port=8082

# ─── IBM MQ ────────────────────────────────────────────────────────────────
mq.host=localhost
mq.port=1414
mq.channel=SYSTEM.DEF.SVRCONN
mq.queueManager=MY.TEST.QMNGR

# ─── AWS S3 / LocalStack ────────────────────────────────────────────────────
# true  → S3Client points at LocalStack (local dev)
# false → S3Client uses DefaultCredentialsProvider (production)
aws.localstack.enabled=true
aws.localstack.endpoint=http://localhost:4566
aws.region=us-east-1
aws.accessKeyId=test
aws.secretAccessKey=test

# S3 bucket holding incoming payment XML files.
# paymentFilePath in request messages is a bucket-relative key (no s3:// prefix).
aws.s3.bucket=fwb-payments-dev
```

All MQ and AWS properties can be overridden at runtime using Spring's standard mechanisms:
- Environment variables: `MQ_HOST=prod-mq-host`
- System properties: `-Dmq.host=prod-mq-host`
- A profile-specific properties file: `application-prod.properties`

---

## Running Locally

### Prerequisites

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Runtime |
| Maven | 3.8+ | Build |
| IBM MQ | 9.x | Queue Manager `MY.TEST.QMNGR` on `localhost:1414` |
| LocalStack | 3.x | Mocks S3 on `localhost:4566` |

### Build and run

```bash
mvn clean package -DskipTests
java -jar target/fwb-file-process-service-1.0-SNAPSHOT.jar
```

Or run directly from Maven:

```bash
mvn spring-boot:run
```

### Override properties at startup

```bash
java -jar target/fwb-file-process-service-1.0-SNAPSHOT.jar \
  -Dmq.host=my-mq-host \
  -Dmq.port=1414 \
  -Dmq.queueManager=PROD.QMNGR \
  -Daws.localstack.enabled=false \
  -Daws.region=eu-west-1 \
  -Daws.s3.bucket=fwb-payments-prod
```

---

## LocalStack Setup

LocalStack mimics AWS S3 locally. No real AWS account or credentials are needed.

### Start LocalStack

```bash
# Using Docker directly
docker run --rm -p 4566:4566 localstack/localstack

# Or using Docker Compose (recommended)
```

`docker-compose.yml` example:

```yaml
version: "3.8"
services:
  localstack:
    image: localstack/localstack:3
    ports:
      - "4566:4566"
    environment:
      - SERVICES=s3
      - AWS_DEFAULT_REGION=us-east-1
```

```bash
docker-compose up -d
```

### Create the bucket and upload a test file

Use the AWS CLI with a LocalStack endpoint. The credentials can be anything non-blank.

```bash
# Configure a local profile (one-time setup)
aws configure --profile localstack
# AWS Access Key ID:     test
# AWS Secret Access Key: test
# Default region:        us-east-1
# Output format:         json

# Create the bucket
aws --profile localstack \
    --endpoint-url http://localhost:4566 \
    s3 mb s3://fwb-payments-dev

# Upload a test payment file
aws --profile localstack \
    --endpoint-url http://localhost:4566 \
    s3 cp /path/to/local/pain008_sample.xml \
    s3://fwb-payments-dev/FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/pain008_sample.xml

# Verify it's there
aws --profile localstack \
    --endpoint-url http://localhost:4566 \
    s3 ls s3://fwb-payments-dev/FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/
```

### Send a test request message to IBM MQ

Put a `TextMessage` onto `SYNTAX.VALIDATION.REQUEST.QUEUE` with:

- **JMSCorrelationID:** any non-blank string (e.g. `test-correlation-001`)
- **Body:**

```json
{
  "paymentFilePath": "FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/pain008_sample.xml"
}
```

The service will download the file from LocalStack, validate it, and write the result to `SYNTAX.VALIDATION.RESPONSE.QUEUE` with the same `JMSCorrelationID`.

### Expected console output (happy path)

```
================================================================================
SyntaxValidationRequestListener: message received
  JMSMessageID    : ID:414d5120...
  Correlation ID  : test-correlation-001
  Request Payload : {"paymentFilePath":"FWB_DIRECT_DEBIT/.../pain008_sample.xml"}
  S3 URI          : FWB_DIRECT_DEBIT/.../pain008_sample.xml
  [S3FileDownloader] downloading s3://fwb-payments-dev/FWB_DIRECT_DEBIT/.../pain008_sample.xml
  [S3FileDownloader] ✓ downloaded 4821 bytes from s3://fwb-payments-dev/FWB_DIRECT_DEBIT/.../pain008_sample.xml
  [SyntaxValidator] ✓ VALID
  Validation result: SyntaxValidationResponse{status='VALID', errorCode='null', errorMessage='null'}
  ✓ Response sent to SYNTAX.VALIDATION.RESPONSE.QUEUE
    Payload        : {"status":"VALID","errorCode":"","errorMessage":""}
    Correlation ID : test-correlation-001
================================================================================
```
