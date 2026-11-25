package ru.petrovov.iib_camel.runtime_lite.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.stream.IntStream;

import static jakarta.xml.soap.SOAPConstants.SOAP_1_1_PROTOCOL;
import static jakarta.xml.soap.SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE;
import static jakarta.xml.soap.SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE;
import static jakarta.xml.soap.SOAPConstants.URI_SOAP_ACTOR_NEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.mockStatic;

class SoapServiceTest {

    private final SoapService service = new SoapService(32_000, 32, 1_000);

    @Test
    void extractsDocumentPreservingNamespacesUsedOnlyInQNames() throws Exception {
        String envelope = """
            <s:Envelope xmlns:s="%s" xmlns="urn:default" xmlns:v="urn:outer" xmlns:h="urn:headers">
              <s:Header><h:trace>ignored-header-value</h:trace></s:Header>
              <s:Body xmlns:v="urn:inner"><item kind="v:Kind">
                <name>v:Value</name><name xmlns:v="urn:local">v:Value</name>
              </item></s:Body>
            </s:Envelope>
            """.formatted(URI_NS_SOAP_1_1_ENVELOPE);

        String xml = service.unwrap(envelope);
        Element root = parse(xml);
        assertThat(root.getLocalName()).isEqualTo("item");
        assertThat(root.getNamespaceURI()).isEqualTo("urn:default");
        assertThat(root.getAttribute("kind")).isEqualTo("v:Kind");
        assertThat(root.lookupNamespaceURI("v")).isEqualTo("urn:inner");
        var children = root.getElementsByTagNameNS("urn:default", "name");
        assertThat(children.item(0).lookupNamespaceURI("v")).isEqualTo("urn:inner");
        assertThat(children.item(1).lookupNamespaceURI("v")).isEqualTo("urn:local");
        assertThat(children.item(0).getTextContent()).isEqualTo("v:Value");
        assertThat(xml).doesNotContain("ignored-header-value");
    }

    @Test
    void wrapsAndUnwrapsSingleDocumentWithoutForwardingHeaders() throws Exception {
        String payload = "<?xml version='1.0'?><item xmlns='urn:example:items'><label>été &amp; чай</label></item>";
        String message = service.wrap(payload);
        Element envelope = parse(message);

        assertThat(envelope.getNamespaceURI()).isEqualTo(URI_NS_SOAP_1_1_ENVELOPE);
        assertThat(envelope.getLocalName()).isEqualTo("Envelope");
        assertThat(envelope.getElementsByTagNameNS(URI_NS_SOAP_1_1_ENVELOPE, "Header").getLength()).isZero();
        Element roundTrip = parse(service.unwrap(message));
        assertThat(roundTrip.getNamespaceURI()).isEqualTo("urn:example:items");
        assertThat(roundTrip.getTextContent()).isEqualTo("été & чай");
    }

    @Test
    void preservesDefaultNamespaceUndeclarationsAndXmlSerialization() throws Exception {
        String envelope = """
            <Envelope xmlns="%s"><Body><html xmlns=""><br/></html></Body></Envelope>
            """.formatted(URI_NS_SOAP_1_1_ENVELOPE);
        String payload = service.unwrap(envelope);
        Element root = parse(payload);
        assertThat(root.getNamespaceURI()).isNull();
        assertThat(root.getFirstChild().getNamespaceURI()).isNull();
        assertThat(parse(service.unwrap(service.wrap(payload))).getFirstChild().getNodeName()).isEqualTo("br");
    }

    @Test
    void recognizesFaultWithoutPublishingUntrustedTextOrDetail() {
        String envelope = withBody("""
            <s:Fault><faultcode>s:Server</faultcode><faultstring>private reason</faultstring>
            <detail><reason>private detail</reason></detail></s:Fault>
            """);

        var fault = assertThrowsExactly(SoapFaultException.class, () -> service.unwrap(envelope));
        assertThat(fault.faultCode()).isEqualTo(new QName(URI_NS_SOAP_1_1_ENVELOPE, "Server"));
        assertThat(fault).hasMessage("SOAP 1.1 Fault received").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<faultstring>text</faultstring>",
        "<faultcode>x:Code</faultcode><faultstring>text</faultstring>",
        "<faultcode>s:bad:code</faultcode><faultstring>text</faultstring>",
        "<faultcode>s:Server</faultcode><faultstring><item/></faultstring>",
        "<faultcode>s:Server</faultcode><faultstring>text</faultstring><faultstring>other</faultstring>"
    })
    void rejectsMalformedFault(String fields) {
        assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap(withBody("<s:Fault>" + fields + "</s:Fault>")));
    }

    @Test
    void rejectsMandatoryHeadersForTheEndpointAndTheNextActorEvenOnFaults() {
        String fault = "<s:Fault><faultcode>s:Server</faultcode><faultstring>reason</faultstring></s:Fault>";
        for (String actor : new String[] {"", " s:actor='   '", " s:actor='" + URI_SOAP_ACTOR_NEXT + "'",
            " s:actor='  " + URI_SOAP_ACTOR_NEXT + "  '"}) {
            String header = "<s:Header><h:security xmlns:h='urn:example:security' s:mustUnderstand='1'" + actor
                + "/></s:Header>";
            assertThatThrownBy(() -> service.unwrap(envelope(header + "<s:Body>" + fault + "</s:Body>")))
                .isExactlyInstanceOf(SoapProcessingException.class).hasMessageContaining("mandatory");
        }
    }

    @Test
    void ignoresOptionalHeadersAndMandatoryHeadersForAnotherActor() throws Exception {
        String header = """
            <s:Header xmlns:h="urn:example:trace"><h:trace s:mustUnderstand="0"/>
              <h:trace s:mustUnderstand="1" s:actor="urn:example:other"/></s:Header>
            """;
        String xml = service.unwrap(envelope(header + "<s:Body><item/></s:Body>"));
        assertThat(parse(xml).getLocalName()).isEqualTo("item");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<s:Header/>", "<s:Body/>", "<s:Body><one/><two/></s:Body>", "<s:Body>text<one/></s:Body>",
        "<s:Body><item/></s:Body><s:Header/>", "<s:Body><item/></s:Body><s:Body><other/></s:Body>",
        "<s:Header><item/></s:Header><s:Body><item/></s:Body>",
        "<s:Header><h:item xmlns:h='urn:example:h' s:mustUnderstand='true'/></s:Header><s:Body><item/></s:Body>",
        "<s:Body s:encodingStyle='urn:example:encoding'><item/></s:Body>"
    })
    void rejectsInvalidSoapStructure(String contents) {
        assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap(envelope(contents)));
    }

    @Test
    void rejectsWrongVersionAndNestedEnvelopesInEitherDirection() {
        String message = withBody("<item/>");
        String soap12 = message.replace(URI_NS_SOAP_1_1_ENVELOPE, URI_NS_SOAP_1_2_ENVELOPE);
        assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap(soap12));
        for (String nested : new String[] {message, soap12, "<batch>" + message + "</batch>"}) {
            assertThrowsExactly(SoapProcessingException.class, () -> service.wrap(nested));
            assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap(withBody(nested)));
        }
    }

    @Test
    void forbidsDocumentTypesAndProcessingInstructionsInEitherDirection() {
        String declaration = "<!DOCTYPE item [<!ENTITY example 'value'>]>";
        assertThrowsExactly(SoapProcessingException.class, () -> service.wrap(declaration + "<item/>"));
        assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap(declaration + withBody("<item/>")));
        assertThrowsExactly(SoapProcessingException.class, () -> service.wrap("<item><?sample info?></item>"));
        assertThrowsExactly(SoapProcessingException.class, () -> service.unwrap("<?sample info?>" + withBody("<item/>")));
    }

    @Test
    void enforcesWireLimitsInBothDirectionsIncludingGeneratedWrapper() {
        var exact = new SoapService(1024, 3, 3);
        assertThat(exact.unwrap(exact.wrap("<item/>"))).contains("item");

        var shallow = new SoapService(1024, 3, 10);
        assertThrowsExactly(SoapProcessingException.class, () -> shallow.wrap("<one><two/></one>"));
        assertThrowsExactly(SoapProcessingException.class, () -> shallow.unwrap(withBody("<one><two/></one>")));

        var fewElements = new SoapService(1024, 10, 4);
        String wide = "<items><a/><b/></items>";
        assertThrowsExactly(SoapProcessingException.class, () -> fewElements.wrap(wide));
        assertThrowsExactly(SoapProcessingException.class, () -> fewElements.unwrap(withBody(wide)));

        var small = new SoapService(30, 10, 10);
        assertThrowsExactly(SoapProcessingException.class, () -> small.wrap("<item/>"));
        assertThrowsExactly(SoapProcessingException.class, () -> small.unwrap(withBody("<item/>")));
    }

    @Test
    void failsAtConstructionWhenSoapProviderIsUnavailable() {
        var missingProvider = new SOAPException("Provider unavailable");
        try (var discovery = mockStatic(MessageFactory.class)) {
            discovery.when(() -> MessageFactory.newInstance(SOAP_1_1_PROTOCOL)).thenThrow(missingProvider);
            assertThatThrownBy(() -> new SoapService(1024, 10, 10))
                .isInstanceOf(IllegalStateException.class).hasCause(missingProvider);
        }
    }

    @Test
    void supportsConcurrentUse() {
        IntStream.range(0, 32).parallel().forEach(index -> {
            String xml = service.unwrap(service.wrap("<item>value" + index + "</item>"));
            assertThat(xml).contains(">value" + index + "</item>");
        });
    }

    private static String withBody(String payload) {
        return envelope("<s:Body>" + payload + "</s:Body>");
    }

    private static String envelope(String contents) {
        return "<s:Envelope xmlns:s='" + URI_NS_SOAP_1_1_ENVELOPE + "'>" + contents + "</s:Envelope>";
    }

    private static Element parse(String xml) throws Exception {
        return DocumentBuilderFactory.newDefaultNSInstance().newDocumentBuilder()
            .parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
}
