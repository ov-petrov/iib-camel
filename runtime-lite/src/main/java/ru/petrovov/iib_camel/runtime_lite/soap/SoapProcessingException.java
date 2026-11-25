package ru.petrovov.iib_camel.runtime_lite.soap;

import ru.petrovov.iib_camel.runtime_lite.xml.XmlProcessingException;

public class SoapProcessingException extends XmlProcessingException {

    public SoapProcessingException(String message) {
        super(message);
    }
}
