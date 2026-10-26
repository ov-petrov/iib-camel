package ru.petrovov.iib_camel.converter.msgflow;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsgFlowParserInventoryTest {
    private final MsgFlowParser parser = new MsgFlowParser();

    @Test
    void extractsPromotedPropertiesDefaultsAndEveryOverriddenAttribute() {
        FlowDocument document = parse(promotedFlow(), "shared/Dispatch.subflow");

        assertTrue(document.subflow());
        assertEquals(List.of("Property.destination", "Property.expiry", "Property.empty"),
                document.properties().stream().map(FlowDocument.PropertyDefinition::id).toList());
        assertEquals("destination", document.properties().getFirst().name());
        assertEquals("SYNTHETIC.DEFAULT", document.properties().getFirst().defaultValue());
        assertNull(document.properties().get(1).defaultValue());
        assertEquals("", document.properties().get(2).defaultValue());

        assertEquals(3, document.propertyLinks().size());
        assertEquals("Property.destination", document.propertyLinks().getFirst().promotedAttribute());
        assertEquals(List.of("SendA", "SendB"), document.propertyLinks().getFirst().overriddenNodeIds());
        assertEquals("queueName", document.propertyLinks().getFirst().targetProperty());
        assertEquals("destinationQueueManagerName", document.propertyLinks().get(1).targetProperty());
        assertEquals("expiry", document.propertyLinks().get(2).targetProperty());
        assertEquals("shared/Dispatch.subflow", document.propertyLinks().getFirst().source().file());
    }

    @Test
    void providesXmlLocationsForRootNodesConnectionsAndDefinitions() {
        String xml = """
                <e:EPackage xmlns:e="http://www.eclipse.org/emf/2002/Ecore"
                  xmlns:x="http://www.omg.org/XMI" xmlns:mq="ComIbmMQOutput.msgnode" nsURI="Located.msgflow">
                  <nodes x:type="mq:FCMComposite_1" x:id="SendA" queueName="SYNTHETIC.A"/>
                  <nodes x:type="mq:FCMComposite_1" x:id="SendB" queueName="SYNTHETIC.B"/>
                  <connections sourceNode="SendA" targetNode="SendB" sourceTerminalName="out" targetTerminalName="in"/>
                </e:EPackage>
                """;
        FlowDocument document = parse(xml, "flows/Located.msgflow");

        assertFalse(document.subflow());
        assertEquals(2, document.source().line());
        assertEquals(3, document.nodeSource("SendA").line());
        assertEquals(4, document.nodeSource("SendB").line());
        assertEquals(5, document.connectionSources().getFirst().line());
        assertTrue(document.nodeSource("SendA").column() > 0);
        assertEquals("flows/Located.msgflow", document.nodeSource("SendA").file());
        assertEquals(document.source(), document.nodeSource("missing"));

        FlowDocument promoted = parse(promotedFlow(), "shared/Dispatch.subflow");
        assertEquals(5, promoted.properties().getFirst().source().line());
        assertTrue(promoted.propertyLinks().getFirst().source().line() > 5);
    }

    @Test
    void keepsNamespaceResolutionAfterRenamingPrefixes() {
        String renamed = promotedFlow().replace("xmlns:e=", "xmlns:meta=")
                .replace("e:", "meta:")
                .replace("xmlns:x=", "xmlns:identity=")
                .replace("x:", "identity:")
                .replace("xmlns:mq=", "xmlns:send=")
                .replace("mq:", "send:");
        FlowDocument document = parse(renamed, "shared/Dispatch.SUBFLOW");

        assertTrue(document.subflow());
        assertEquals("MQOutput", document.flow().node("SendA").kind());
        assertEquals("SYNTHETIC.DEFAULT", document.properties().getFirst().defaultValue());
        assertEquals("queueName", document.propertyLinks().getFirst().targetProperty());
    }

    @Test
    void inventoryAndStrictParsingProduceTheSameFlowAndPreserveCustomProperties() {
        String xml = promotedFlow();
        FlowDocument document = parse(xml, "shared/Dispatch.subflow");

        assertEquals(parser.parse(input(xml)), document.flow());
        assertEquals("custom-value", document.flow().node("SendA").properties().get("customProperty"));
        assertEquals("urn:custom", document.flow().node("SendA").properties().get("xmlns:custom"));
        assertEquals("value", document.flow().node("SendA").properties().get("custom:attribute"));
    }

    @Test
    void preservesStrictGraphValidationForInventory() {
        String dangling = promotedFlow().replace("targetNode=\"SendB\"", "targetNode=\"Missing\"");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(dangling, "shared/Dispatch.subflow"));

        assertTrue(error.getMessage().contains("отсутствующий targetNode"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(input(dangling)));
    }

    @Test
    void refusesDoctypeExternalEntitiesAndMalformedXmlInBothModes() {
        String external = "<!DOCTYPE e:EPackage [<!ENTITY external SYSTEM 'file:///not-readable-by-inventory'>]>"
                + "<e:EPackage xmlns:e=\"http://www.eclipse.org/emf/2002/Ecore\" nsURI=\"&external;\"/>";
        for (String xml : List.of(external, "<e:EPackage")) {
            assertThrows(IllegalArgumentException.class, () -> parse(xml, "Unsafe.msgflow"));
            assertThrows(IllegalArgumentException.class, () -> parser.parse(input(xml)));
        }
    }

    private FlowDocument parse(String xml, String sourceFile) {
        return parser.parseDocument(input(xml), sourceFile);
    }

    private ByteArrayInputStream input(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private String promotedFlow() {
        return """
                <e:EPackage xmlns:e="http://www.eclipse.org/emf/2002/Ecore" xmlns:x="http://www.omg.org/XMI"
                  xmlns:mq="ComIbmMQOutput.msgnode" nsURI="shared/Dispatch.subflow">
                  <eClassifiers x:type="e:EClass" name="Dispatch">
                    <eStructuralFeatures x:type="e:EAttribute" x:id="Property.destination" name="destination"
                      defaultValueLiteral="SYNTHETIC.DEFAULT"/>
                    <eStructuralFeatures x:type="e:EAttribute" x:id="Property.expiry" name="expiry"/>
                    <eStructuralFeatures x:type="e:EAttribute" x:id="Property.empty" name="empty" defaultValueLiteral=""/>
                    <eStructuralFeatures x:type="e:EReference" x:id="Reference.ignored" name="ignored"/>
                    <composition>
                      <nodes x:type="mq:FCMComposite_1" x:id="SendA" xmlns:custom="urn:custom"
                        customProperty="custom-value" custom:attribute="value"/>
                      <nodes x:type="mq:FCMComposite_1" x:id="SendB"/>
                      <connections sourceNode="SendA" targetNode="SendB" sourceTerminalName="out" targetTerminalName="in"/>
                    </composition>
                    <attributeLinks promotedAttribute="Property.destination" overriddenNodes="SendA  SendB">
                      <overriddenAttribute href="ComIbmMQOutput.msgnode#Property.queueName"/>
                      <overriddenAttribute href="ComIbmMQOutput.msgnode#Property.destinationQueueManagerName"/>
                    </attributeLinks>
                    <attributeLinks promotedAttribute="Property.expiry" overriddenNodes="SendA">
                      <overriddenAttribute href="Property.expiry"/>
                    </attributeLinks>
                  </eClassifiers>
                </e:EPackage>
                """;
    }
}
