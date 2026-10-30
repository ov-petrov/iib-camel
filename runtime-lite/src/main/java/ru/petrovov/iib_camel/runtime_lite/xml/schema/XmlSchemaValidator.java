package ru.petrovov.iib_camel.runtime_lite.xml.schema;

import lombok.RequiredArgsConstructor;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.sax.SAXSource;
import java.io.StringReader;
import java.util.Objects;

@RequiredArgsConstructor
public class XmlSchemaValidator {

    private final XmlSchemaRegistry schemas;

    public void validate(String xml, String schemaLocation) {
        Objects.requireNonNull(xml, "xml");
        var schema = schemas.get(schemaLocation);
        try {
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
                throw new XmlSchemaException("External schema resources are forbidden during XML validation");
            });
            validator.validate(new SAXSource(SecureSax.reader(), new InputSource(new StringReader(xml))));
        } catch (SAXParseException e) {
            throw new XmlSchemaException("XML does not match schema " + schemaLocation
                + " at line " + e.getLineNumber() + ", column " + e.getColumnNumber());
        } catch (Exception e) {
            throw new XmlSchemaException("Could not validate XML against schema " + schemaLocation);
        }
    }
}
