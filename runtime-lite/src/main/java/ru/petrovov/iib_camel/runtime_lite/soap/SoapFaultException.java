package ru.petrovov.iib_camel.runtime_lite.soap;

import javax.xml.namespace.QName;

public final class SoapFaultException extends SoapProcessingException {

    private final QName faultCode;

    SoapFaultException(QName faultCode) {
        super("SOAP 1.1 Fault received");
        this.faultCode = faultCode;
    }

    public QName faultCode() {
        return faultCode;
    }
}
