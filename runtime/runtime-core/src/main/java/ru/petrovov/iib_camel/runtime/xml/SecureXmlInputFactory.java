package ru.petrovov.iib_camel.runtime.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.NoSuchElementException;
import javax.xml.stream.EventFilter;
import javax.xml.stream.StreamFilter;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLReporter;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.EventReaderDelegate;
import javax.xml.stream.util.StreamReaderDelegate;
import javax.xml.stream.util.XMLEventAllocator;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;

final class SecureXmlInputFactory extends XMLInputFactory {
    private final XMLInputFactory delegate = XMLInputFactory.newFactory();

    SecureXmlInputFactory() {
        delegate.setProperty(SUPPORT_DTD, false);
        delegate.setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        delegate.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });
    }

    private static XMLStreamReader secure(XMLStreamReader reader) {
        return new StreamReaderDelegate(reader) {
            @Override public int next() throws XMLStreamException {
                int event = super.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD declarations are forbidden");
                return event;
            }

            @Override public int nextTag() throws XMLStreamException {
                int event = next();
                while ((event == XMLStreamConstants.CHARACTERS && isWhiteSpace())
                        || (event == XMLStreamConstants.CDATA && isWhiteSpace())
                        || event == XMLStreamConstants.SPACE || event == XMLStreamConstants.PROCESSING_INSTRUCTION
                        || event == XMLStreamConstants.COMMENT) event = next();
                if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                    throw new XMLStreamException("Expected XML element", getLocation());
                }
                return event;
            }
        };
    }

    private static XMLEventReader secure(XMLEventReader reader) {
        return new EventReaderDelegate(reader) {
            private XMLEvent check(XMLEvent event) throws XMLStreamException {
                if (event != null && event.getEventType() == XMLStreamConstants.DTD) throw new XMLStreamException("DTD declarations are forbidden");
                return event;
            }
            @Override public XMLEvent nextEvent() throws XMLStreamException { return check(super.nextEvent()); }
            @Override public Object next() {
                try { return nextEvent(); }
                catch (XMLStreamException e) { throw new NoSuchElementException(e.getMessage(), e); }
            }
            @Override public XMLEvent peek() throws XMLStreamException { return check(super.peek()); }
            @Override public XMLEvent nextTag() throws XMLStreamException { return check(super.nextTag()); }
        };
    }

    @Override public XMLStreamReader createXMLStreamReader(Reader reader) throws XMLStreamException { return secure(delegate.createXMLStreamReader(reader)); }
    @Override public XMLStreamReader createXMLStreamReader(Source source) throws XMLStreamException { return secure(delegate.createXMLStreamReader(source)); }
    @Override public XMLStreamReader createXMLStreamReader(InputStream stream) throws XMLStreamException { return secure(delegate.createXMLStreamReader(stream)); }
    @Override public XMLStreamReader createXMLStreamReader(InputStream stream, String encoding) throws XMLStreamException { return secure(delegate.createXMLStreamReader(stream, encoding)); }
    @Override public XMLStreamReader createXMLStreamReader(String systemId, InputStream stream) throws XMLStreamException { return secure(delegate.createXMLStreamReader(systemId, stream)); }
    @Override public XMLStreamReader createXMLStreamReader(String systemId, Reader reader) throws XMLStreamException { return secure(delegate.createXMLStreamReader(systemId, reader)); }
    @Override public XMLEventReader createXMLEventReader(Reader reader) throws XMLStreamException { return secure(delegate.createXMLEventReader(reader)); }
    @Override public XMLEventReader createXMLEventReader(String systemId, Reader reader) throws XMLStreamException { return secure(delegate.createXMLEventReader(systemId, reader)); }
    @Override public XMLEventReader createXMLEventReader(XMLStreamReader reader) throws XMLStreamException { return secure(delegate.createXMLEventReader(secure(reader))); }
    @Override public XMLEventReader createXMLEventReader(Source source) throws XMLStreamException { return secure(delegate.createXMLEventReader(source)); }
    @Override public XMLEventReader createXMLEventReader(InputStream stream) throws XMLStreamException { return secure(delegate.createXMLEventReader(stream)); }
    @Override public XMLEventReader createXMLEventReader(InputStream stream, String encoding) throws XMLStreamException { return secure(delegate.createXMLEventReader(stream, encoding)); }
    @Override public XMLEventReader createXMLEventReader(String systemId, InputStream stream) throws XMLStreamException { return secure(delegate.createXMLEventReader(systemId, stream)); }
    @Override public XMLStreamReader createFilteredReader(XMLStreamReader reader, StreamFilter filter) throws XMLStreamException { return secure(delegate.createFilteredReader(secure(reader), filter)); }
    @Override public XMLEventReader createFilteredReader(XMLEventReader reader, EventFilter filter) throws XMLStreamException { return secure(delegate.createFilteredReader(secure(reader), filter)); }
    @Override public XMLResolver getXMLResolver() { return delegate.getXMLResolver(); }
    @Override public void setXMLResolver(XMLResolver resolver) { delegate.setXMLResolver(resolver); }
    @Override public XMLReporter getXMLReporter() { return delegate.getXMLReporter(); }
    @Override public void setXMLReporter(XMLReporter reporter) { delegate.setXMLReporter(reporter); }
    @Override public void setProperty(String name, Object value) { delegate.setProperty(name, value); }
    @Override public Object getProperty(String name) { return delegate.getProperty(name); }
    @Override public boolean isPropertySupported(String name) { return delegate.isPropertySupported(name); }
    @Override public void setEventAllocator(XMLEventAllocator allocator) { delegate.setEventAllocator(allocator); }
    @Override public XMLEventAllocator getEventAllocator() { return delegate.getEventAllocator(); }
}
