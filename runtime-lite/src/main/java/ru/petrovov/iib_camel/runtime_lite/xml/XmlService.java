package ru.petrovov.iib_camel.runtime_lite.xml;

import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaValidator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.dataformat.xml.XmlMapper;

import javax.xml.namespace.QName;
import java.util.Objects;

@RequiredArgsConstructor
public class XmlService {

    private final XmlMapper xmlMapper;
    private final XmlSchemaValidator schemaValidator;

    public QName detectRoot(byte[] xml) {
        return new XmlDocumentInspector().inspect(Objects.requireNonNull(xml, "xml"));
    }

    public <T> T read(byte[] xml, Class<T> type) {
        Objects.requireNonNull(type, "type");
        detectRoot(xml);
        try {
            return xmlMapper.readerFor(type).readValue(xml);
        } catch (JacksonException exception) {
            throw new XmlProcessingException("Cannot map XML to " + type.getName());
        }
    }

    public <T> T readValidated(byte[] xml, String schemaLocation, Class<T> type) {
        Objects.requireNonNull(type, "type");
        detectRoot(xml);
        schemaValidator.validate(xml, schemaLocation);
        try {
            return xmlMapper.readerFor(type).readValue(xml);
        } catch (JacksonException exception) {
            throw new XmlProcessingException("Cannot map XML to " + type.getName());
        }
    }

    public ObjectWriter writerFor(Class<?> type) {
        return xmlMapper.writerFor(Objects.requireNonNull(type, "type"));
    }

    public byte[] write(Object value) {
        Objects.requireNonNull(value, "value");
        return write(value, writerFor(value.getClass()));
    }

    public byte[] write(Object value, ObjectWriter writer) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(writer, "writer");
        try {
            byte[] xml = writer.writeValueAsBytes(value);
            detectRoot(xml);
            return xml;
        } catch (JacksonException exception) {
            throw new XmlProcessingException("Cannot serialize XML from " + value.getClass().getName());
        }
    }

    public byte[] writeValidated(Object value, String schemaLocation) {
        byte[] xml = write(value);
        schemaValidator.validate(xml, schemaLocation);
        return xml;
    }

    public byte[] writeValidated(Object value, ObjectWriter writer, String schemaLocation) {
        byte[] xml = write(value, writer);
        schemaValidator.validate(xml, schemaLocation);
        return xml;
    }
}
