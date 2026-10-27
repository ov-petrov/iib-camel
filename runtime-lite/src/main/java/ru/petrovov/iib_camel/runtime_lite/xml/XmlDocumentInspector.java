package ru.petrovov.iib_camel.runtime_lite.xml;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;

final class XmlDocumentInspector {

    QName inspect(byte[] xml) {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });

        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                QName root = null;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) {
                        throw new XmlProcessingException("DTD declarations are forbidden");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT && root == null) {
                        root = reader.getName();
                    }
                }
                if (root == null) {
                    throw new XmlProcessingException("XML document has no root element");
                }
                return root;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException exception) {
            var location = exception.getLocation();
            String position = location == null ? "" : " at line " + location.getLineNumber()
                + ", column " + location.getColumnNumber();
            throw new XmlProcessingException("Cannot parse XML document" + position);
        }
    }
}
