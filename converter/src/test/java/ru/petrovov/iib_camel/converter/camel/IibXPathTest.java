package ru.petrovov.iib_camel.converter.camel;

import org.junit.jupiter.api.Test;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IibXPathTest {
    private static final String BODY = "<Order><amount>100</amount><status>HIGH</status><code>0100</code></Order>";

    @Test
    void preservesNumericComparisons() throws Exception {
        for (String comparison : List.of("=100", "!=99", ">99", ">=100", "<101", "<=100")) {
            assertEquals(true, evaluate("$Body/Order/amount" + comparison, BODY), comparison);
        }
        for (String comparison : List.of("=99", "!=100", ">100", ">=101", "<100", "<=99")) {
            assertEquals(false, evaluate("$Body/Order/amount" + comparison, BODY), comparison);
        }
    }

    @Test
    void preservesStringComparisonAndLeadingZeros() throws Exception {
        assertEquals(true, evaluate("$Body/Order/status='HIGH'", BODY));
        assertEquals(false, evaluate("$Body/Order/status='high'", BODY));
        assertEquals(true, evaluate("$Body/Order/code=100", BODY));
        assertEquals(false, evaluate("$Body/Order/code='100'", BODY));
        assertEquals(true, evaluate("$Body/Order/code='0100'", BODY));
    }

    @Test
    void mapsXmlParserDomainAndSupportsBooleanXPathExpressions() throws Exception {
        assertEquals(true, evaluate("$Root/XMLNSC/Order/amount>=100 and $Body/Order/status='HIGH'", BODY));
        assertEquals(true, evaluate("$Root/XMLNS/Order[status='HIGH']/amount=100", BODY));
        assertEquals(false, evaluate("$Body/Order/missing", BODY));
        assertEquals(true, evaluate("count($Body/Order/amount)=1", BODY));
    }

    @Test
    void leavesVariableLikeTextInsideStringsUntouched() throws Exception {
        String pattern = "$Body/Order/status='$Root/JSON:nothing'";
        assertEquals("/Order/status='$Root/JSON:nothing'", IibXPath.translate(pattern));
        assertEquals(true, evaluate(pattern, "<Order><status>$Root/JSON:nothing</status></Order>"));
    }

    @Test
    void rejectsUnavailableMessageTreesNamespacesAndParentNavigation() {
        for (String pattern : List.of("$Environment/Value='x'", "$Root/MQMD/Format='x'",
                "$Root/XMLNSC2/Order=1", "$Body/n:Order=1", "$Body/Order/../MQMD=1",
                "$Body/Order/parent::node()", "$Unknown=1")) {
            assertThrows(IllegalArgumentException.class, () -> IibXPath.translate(pattern), pattern);
        }
    }

    @Test
    void rejectsMalformedXPathAndUnknownFunctions() {
        for (String pattern : List.of("", "$Body/Order/amount>=", "$Body/Order/status='HIGH",
                "$Body/Order/amount==100", "unknownFunction($Body/Order)", "$Body/Order[")) {
            assertThrows(IllegalArgumentException.class, () -> IibXPath.translate(pattern), pattern);
        }
    }

    private boolean evaluate(String pattern, String body) throws Exception {
        return (Boolean) XPathFactory.newInstance().newXPath().evaluate(IibXPath.translate(pattern),
                new InputSource(new StringReader(body)), XPathConstants.BOOLEAN);
    }
}
