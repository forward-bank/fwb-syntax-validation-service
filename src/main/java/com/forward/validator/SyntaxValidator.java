package com.forward.validator;

import com.forward.model.SyntaxValidationResponse;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a SEPA Direct Debit pain.008.001.08 XML file against the bundled XSD schema.
 *
 * The XSD is loaded once at construction time from the classpath and reused for every
 * validation call — schema compilation is expensive; the compiled {@link Schema} object
 * is thread-safe and can be shared.
 *
 * Each call to {@link #validate(byte[])} creates a fresh {@link Validator} instance
 * because Validator is NOT thread-safe.
 */
@Component
public class SyntaxValidator {

    private static final String XSD_CLASSPATH = "/xsd/pain008/pain_008_001_08.xsd";

    /** Compiled XSD schema — thread-safe, built once. */
    private final Schema schema;

    public SyntaxValidator() {
        this.schema = loadSchema();
    }

    /**
     * Validates the provided XML bytes against the pain.008.001.08 XSD.
     *
     * @param xmlBytes raw bytes of the payment XML file downloaded from S3
     * @return {@link SyntaxValidationResponse#valid()} on success, or
     *         {@link SyntaxValidationResponse#invalid(String)} with an error code and
     *         message on failure
     */
    public SyntaxValidationResponse validate(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return SyntaxValidationResponse.invalid("SVE_001", "Empty or null XML content");
        }

        // Collect all validation errors rather than stopping at the first one
        List<String> errors = new ArrayList<>();

        try {
            Validator validator = schema.newValidator();

            // Custom error handler — accumulates all errors instead of throwing on the first
            validator.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    System.out.println("  [SyntaxValidator] WARN  line " + e.getLineNumber() + ": " + e.getMessage());
                }
                @Override
                public void error(SAXParseException e) {
                    errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
                }
                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    errors.add("FATAL line " + e.getLineNumber() + ": " + e.getMessage());
                    throw e;  // fatal errors are unrecoverable — stop parsing
                }
            });

            validator.validate(new StreamSource(new ByteArrayInputStream(xmlBytes)));

        } catch (SAXException e) {
            // Fatal parse error — SAXParseException already added to errors list above;
            // any other SAXException (e.g. not well-formed XML) is caught here
            if (errors.isEmpty()) {
                errors.add(e.getMessage());
            }
        } catch (IOException e) {
            return SyntaxValidationResponse.invalid("SVE_003", "IO error reading XML: " + e.getMessage());
        }

        if (!errors.isEmpty()) {
            String detail = String.join("; ", errors);
            System.out.println("  [SyntaxValidator] ✗ INVALID — " + detail);
            return SyntaxValidationResponse.invalid("SVE_002", detail);
        }

        System.out.println("  [SyntaxValidator] ✓ VALID");
        return SyntaxValidationResponse.valid();
    }

    // ── Schema loading ────────────────────────────────────────────────────────

    private Schema loadSchema() {
        try (InputStream xsdStream = SyntaxValidator.class.getResourceAsStream(XSD_CLASSPATH)) {
            if (xsdStream == null) {
                throw new IllegalStateException(
                        "XSD schema not found on classpath: " + XSD_CLASSPATH);
            }
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema compiled = factory.newSchema(new StreamSource(xsdStream));
            System.out.println("✓ [SyntaxValidator] pain.008.001.08 XSD schema loaded");
            return compiled;
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Failed to compile XSD schema: " + e.getMessage(), e);
        }
    }
}
