package ru.petrovov.iib_camel.runtime_lite.xml.schema;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;

final class SecureSax {

    private SecureSax() {
    }

    static XMLReader reader() throws ParserConfigurationException, SAXException {
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML resources are forbidden");
        });
        reader.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                throw e;
            }
        });
        return reader;
    }

    static InputSource source(byte[] bytes, String systemId) {
        var source = new InputSource(new ByteArrayInputStream(bytes));
        source.setSystemId(systemId);
        return source;
    }
}
