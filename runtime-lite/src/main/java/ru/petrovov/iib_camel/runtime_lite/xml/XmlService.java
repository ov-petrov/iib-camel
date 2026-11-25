package ru.petrovov.iib_camel.runtime_lite.xml;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBIntrospector;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.ValidationEvent;
import jakarta.xml.bind.annotation.XmlRegistry;
import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaValidator;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RequiredArgsConstructor
public class XmlService {

    private final XmlSchemaValidator schemaValidator;
    private final ConcurrentMap<Class<?>, JAXBContext> contexts = new ConcurrentHashMap<>();

    public QName detectRoot(String xml) {
        return new XmlDocumentInspector().inspect(Objects.requireNonNull(xml, "xml"));
    }

    public ValidatedXml validate(String xml) {
        detectRoot(xml);
        return new ValidatedXml(xml);
    }

    public ValidatedXml validate(String xml, String schemaLocation) {
        detectRoot(xml);
        schemaValidator.validate(xml, schemaLocation);
        return new ValidatedXml(xml);
    }

    public <T> T read(ValidatedXml xml, Class<T> type) {
        Objects.requireNonNull(xml, "xml");
        Objects.requireNonNull(type, "type");
        try {
            var unmarshaller = context(type).createUnmarshaller();
            unmarshaller.setEventHandler(event -> event.getSeverity() == ValidationEvent.WARNING);
            var reader = XmlDocumentInspector.reader(xml.text());
            try {
                return type.cast(JAXBIntrospector.getValue(unmarshaller.unmarshal(reader)));
            } finally {
                reader.close();
            }
        } catch (JAXBException | XMLStreamException | ClassCastException exception) {
            throw new XmlProcessingException("Cannot map XML to " + type.getName());
        }
    }

    public <T> T read(String xml, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return read(validate(xml), type);
    }

    public <T> T readValidated(String xml, String schemaLocation, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return read(validate(xml, schemaLocation), type);
    }

    public String write(Object value) {
        Objects.requireNonNull(value, "value");
        Class<?> type = value instanceof JAXBElement<?> element ? element.getDeclaredType() : value.getClass();
        try {
            var marshaller = context(type).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
            var output = new StringWriter();
            marshaller.marshal(value, output);
            String xml = output.toString();
            detectRoot(xml);
            return xml;
        } catch (JAXBException exception) {
            throw new XmlProcessingException("Cannot serialize XML from " + type.getName());
        }
    }

    public String writeValidated(Object value, String schemaLocation) {
        String xml = write(value);
        schemaValidator.validate(xml, schemaLocation);
        return xml;
    }

    public ValidatedXml writeDocument(Object value) {
        return new ValidatedXml(write(value));
    }

    public ValidatedXml writeValidatedDocument(Object value, String schemaLocation) {
        return new ValidatedXml(writeValidated(value, schemaLocation));
    }

    private JAXBContext context(Class<?> type) {
        return contexts.computeIfAbsent(type, this::createContext);
    }

    private JAXBContext createContext(Class<?> type) {
        try {
            Class<?> factory = objectFactory(type);
            return factory == null ? JAXBContext.newInstance(type) : JAXBContext.newInstance(type, factory);
        } catch (JAXBException exception) {
            throw new XmlProcessingException("Cannot create JAXB context for " + type.getName());
        }
    }

    private Class<?> objectFactory(Class<?> type) {
        try {
            Class<?> factory = Class.forName(type.getPackageName() + ".ObjectFactory", false, type.getClassLoader());
            return factory.isAnnotationPresent(XmlRegistry.class) ? factory : null;
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }
}
