package ru.petrovov.iib_camel.runtime.compute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractComputeBeanTest {

    private static final class TestBean extends AbstractComputeBean {
        private final BiConsumer<TestBean, Exchange> action;

        private TestBean(BiConsumer<TestBean, Exchange> action) {
            this.action = action;
        }

        @Override
        protected void main(Exchange exchange) {
            action.accept(this, exchange);
        }
    }

    private final TestBean esql = new TestBean((bean, exchange) -> {});

    private static Exchange exchangeWith(String body) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(body);
        return exchange;
    }

    @Test
    void messageModeStartsWithEmptyOutputAndImmutableInput() {
        Exchange exchange = exchangeWith("<Order><Value>INPUT</Value></Order>");
        exchange.getIn().setHeader("Input", "header");
        new TestBean((bean, message) -> {
            assertNull(message.getIn().getBody());
            assertTrue(message.getIn().getHeaders().isEmpty());
            bean.setBodyField(message, "Result.Value", "OUTPUT");
            assertEquals("INPUT", bean.bodyField(message, true, "Order.Value"));
            assertEquals("OUTPUT", bean.bodyField(message, false, "Result.Value"));
            assertEquals("header", bean.mqmdField(message, true, "Input"));
            assertNull(bean.mqmdField(message, false, "Input"));
        }).compute(exchange);
        assertEquals("<Result><Value>OUTPUT</Value></Result>", exchange.getIn().getBody());
        assertTrue(exchange.getIn().getHeaders().isEmpty());
    }

    @Test
    void nullDeletesFieldsAndNeverCreatesMissingAncestors() {
        Exchange exchange = exchangeWith("<Order><Value>old</Value><Keep>keep</Keep></Order>");
        new TestBean((bean, message) -> {
            bean.copy(message);
            bean.setBodyField(message, "Order.Value", null);
            bean.setBodyField(message, "Order.Absent.Nested", null);
            bean.mqmd(message, "Gone", "old");
            bean.mqmd(message, "Gone", null);
        }).compute(exchange);
        assertEquals("<Order><Keep>keep</Keep></Order>", exchange.getIn().getBody());
        assertNull(exchange.getIn().getHeader("Gone"));
    }

    @Test
    void scalarOperationsPreserveNullAndValidateNumericValues() {
        assertNull(EsqlValues.cast(null, "INTEGER"));
        assertNull(EsqlValues.compare(null, "x", "="));
        assertNull(EsqlValues.not(null));
        assertFalse(EsqlValues.truth(null));
        assertNull(EsqlValues.and(true, null));
        assertNull(EsqlValues.or(false, null));
        assertEquals(false, EsqlValues.and(false, null));
        assertEquals(true, EsqlValues.or(true, null));
        assertEquals(12L, EsqlValues.cast("0012", "INTEGER"));
        assertEquals(true, EsqlValues.compare(12L, new java.math.BigDecimal("12.00"), "="));
        assertThrows(IllegalArgumentException.class, () -> EsqlValues.cast("12x", "INTEGER"));
        assertThrows(IllegalArgumentException.class, () -> EsqlValues.cast("9223372036854775808", "INTEGER"));
        assertThrows(IllegalArgumentException.class, () -> EsqlValues.cast("NaN", "DECIMAL"));
        assertThrows(IllegalArgumentException.class, () -> EsqlValues.cast("yes", "BOOLEAN"));
    }

    @Test
    void copyLeavesBodyUntouched() {
        Exchange exchange = exchangeWith("<Order><Status>NEW</Status></Order>");
        new TestBean((bean, message) -> bean.copy(message)).compute(exchange);
        assertEquals("<Order><Status>NEW</Status></Order>", exchange.getIn().getBody(String.class));
    }

    @Test
    void copyRestoresBodyAndHeadersAfterEdits() {
        String original = "<Order><Status>NEW</Status></Order>";
        Exchange exchange = exchangeWith(original);
        exchange.getIn().setHeader("Original", "kept");
        new TestBean((bean, message) -> {
            bean.setBodyField(message, "Order.Status", "CHANGED");
            message.getIn().removeHeader("Original");
            bean.mqmd(message, "ApplicationIdData", "added");
            bean.copy(message);
            assertEquals(original, message.getIn().getBody(String.class));
            assertEquals(Map.of("Original", "kept"), message.getIn().getHeaders());
            bean.setBodyField(message, "Order.Status", "SECOND");
            bean.mqmd(message, "ApplicationIdData", "again");
            bean.copy(message);
        }).compute(exchange);
        assertEquals(original, exchange.getIn().getBody(String.class));
        assertEquals(Map.of("Original", "kept"), exchange.getIn().getHeaders());
        assertEquals(Map.of(), exchange.getProperties());
    }

    @Test
    void eachComputeSnapshotsItsOwnInputEvenOnSameExchange() {
        TestBean bean = new TestBean((self, message) -> {
            self.setBodyField(message, "Order.Status", "CHANGED");
            self.copy(message);
        });
        Exchange first = exchangeWith("<Order><Status>FIRST</Status></Order>");
        Exchange second = exchangeWith("<Order><Status>SECOND</Status></Order>");
        bean.compute(first);
        bean.compute(second);
        first.getIn().setBody("<Order><Status>THIRD</Status></Order>");
        bean.compute(first);
        assertEquals("<Order><Status>THIRD</Status></Order>", first.getIn().getBody(String.class));
        assertEquals("<Order><Status>SECOND</Status></Order>", second.getIn().getBody(String.class));
        assertEquals(Map.of(), first.getProperties());
        assertEquals(Map.of(), second.getProperties());
    }

    @Test
    void nestedComputeRestoresOuterSnapshot() {
        Exchange exchange = exchangeWith("<Order><Status>INITIAL</Status></Order>");
        new TestBean((outer, message) -> {
            outer.setBodyField(message, "Order.Status", "OUTER");
            new TestBean((inner, nested) -> {
                inner.setBodyField(nested, "Order.Status", "INNER");
                inner.copy(nested);
            }).compute(message);
            assertEquals("<Order><Status>OUTER</Status></Order>", message.getIn().getBody(String.class));
            outer.copy(message);
        }).compute(exchange);
        assertEquals("<Order><Status>INITIAL</Status></Order>", exchange.getIn().getBody(String.class));
        assertEquals(Map.of(), exchange.getProperties());
    }

    @Test
    void sharedBeanKeepsConcurrentExchangeSnapshotsSeparate() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        TestBean bean = new TestBean((self, message) -> {
            entered.countDown();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            self.setBodyField(message, "Order.Status", "CHANGED");
            self.copy(message);
        });
        Exchange first = exchangeWith("<Order><Status>FIRST</Status></Order>");
        Exchange second = exchangeWith("<Order><Status>SECOND</Status></Order>");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstCall = executor.submit(() -> bean.compute(first));
            var secondCall = executor.submit(() -> bean.compute(second));
            firstCall.get(5, TimeUnit.SECONDS);
            secondCall.get(5, TimeUnit.SECONDS);
        }
        assertEquals("<Order><Status>FIRST</Status></Order>", first.getIn().getBody(String.class));
        assertEquals("<Order><Status>SECOND</Status></Order>", second.getIn().getBody(String.class));
        assertEquals(Map.of(), first.getProperties());
        assertEquals(Map.of(), second.getProperties());
    }

    @Test
    void failedComputeCleansSnapshot() {
        Exchange exchange = exchangeWith("<Order/>");
        assertThrows(IllegalStateException.class, () -> new TestBean((bean, message) -> {
            throw new IllegalStateException("failed");
        }).compute(exchange));
        assertEquals(Map.of(), exchange.getProperties());
        assertThrows(IllegalStateException.class, () -> esql.copy(exchange));
    }

    @Test
    void mqmdSetsMessageHeader() {
        Exchange exchange = exchangeWith("<Order/>");
        esql.mqmd(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
        assertEquals("CHECKED-BY-SUBFLOW", exchange.getIn().getHeader("CommentField", String.class));
    }

    @Test
    void setBodyFieldUsesDocumentRootWithoutCopy() {
        Exchange exchange = exchangeWith("<Order><Status>NEW</Status></Order>");
        esql.setBodyField(exchange, "Order.Status", "PAUSED");
        assertEquals("<Order><Status>PAUSED</Status></Order>", exchange.getIn().getBody(String.class));
    }

    @Test
    void setBodyFieldPatchesNestedXmlAndPreservesSiblings() {
        Exchange exchange = exchangeWith("<Adapter><Status>OTHER</Status><Order id=\"42\">"
                + "<Status>NEW</Status><Comment>Keep</Comment></Order></Adapter>");
        esql.setBodyField(exchange, "Adapter.Order.Status", "PAUSED");
        assertEquals("<Adapter><Status>OTHER</Status><Order id=\"42\"><Status>PAUSED</Status>"
                + "<Comment>Keep</Comment></Order></Adapter>", exchange.getIn().getBody(String.class));
    }

    @Test
    void setBodyFieldCreatesMissingLevelsAndEscapesText() {
        Exchange exchange = exchangeWith("<Adapter/>");
        esql.setBodyField(exchange, "Adapter.Order.Status", "ACTIVE & <ready>");
        assertEquals("<Adapter><Order><Status>ACTIVE &amp; &lt;ready&gt;</Status></Order></Adapter>",
                exchange.getIn().getBody(String.class));
    }

    @Test
    void rootMismatchDoesNotModifyBody() {
        Exchange exchange = exchangeWith("<Adapter/>");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> esql.setBodyField(exchange, "Order.Status", "ACTIVE"));
        assertEquals("<Adapter/>", exchange.getIn().getBody(String.class));
        assertEquals("XML root Adapter does not match path root Order", error.getCause().getMessage());
    }

    @Test
    void unsupportedPathsFailWithoutChangingBody() {
        Exchange exchange = exchangeWith("<Order/>");
        for (String path : new String[]{"", ".Order.Status", "Order.Status.", "Order..Status",
                "Order.Status[1]", "Order.@status", "ns:Order.Status", "Order.*", null}) {
            assertThrows(IllegalStateException.class, () -> esql.setBodyField(exchange, path, "X"));
            assertEquals("<Order/>", exchange.getIn().getBody(String.class));
        }
    }

    @Test
    void unsupportedNamespacesFailWithoutChangingBody() {
        for (String body : new String[]{"<Order xmlns=\"urn:orders\"><Status>NEW</Status></Order>",
                "<Order xmlns:x=\"urn:orders\"><x:Status>NEW</x:Status></Order>",
                "<Order xmlns:x=\"urn:orders\" x:id=\"1\"/>"}) {
            Exchange exchange = exchangeWith(body);
            assertThrows(IllegalStateException.class, () -> esql.setBodyField(exchange, "Order.Status", "X"));
            assertEquals(body, exchange.getIn().getBody(String.class));
        }
    }

    @Test
    void emptyAndMalformedBodiesFailWithoutChangingBody() {
        for (String body : new String[]{null, "", " ", "<Order>broken", "{\"status\":\"NEW\"}"}) {
            Exchange exchange = exchangeWith(body);
            assertThrows(IllegalStateException.class, () -> esql.setBodyField(exchange, "Order.Status", "X"));
            assertEquals(body, exchange.getIn().getBody(String.class));
        }
    }

    @Test
    void internalDtdAndEntitiesAreRejected() {
        Exchange exchange = exchangeWith("<!DOCTYPE Order [<!ENTITY secret 'ENTITY'>]>"
                + "<Order><Status>&secret;</Status></Order>");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> esql.setBodyField(exchange, "Order.Status", "X"));
        assertInstanceOf(SAXParseException.class, error.getCause());
    }

    @Test
    void externalDtdFailsAtDoctypeBeforeResolution() {
        String original = "<!DOCTYPE Order SYSTEM 'http://127.0.0.1:1/orders.dtd'><Order/>";
        Exchange exchange = exchangeWith(original);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> esql.setBodyField(exchange, "Order.Status", "X"));
        assertInstanceOf(SAXParseException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("DOCTYPE"));
        assertEquals(original, exchange.getIn().getBody(String.class));
    }

    @Test
    void localExternalDtdIsRejected(@TempDir Path directory) throws Exception {
        Path dtd = directory.resolve("orders.dtd");
        Files.writeString(dtd, "<!ELEMENT Order ANY>\n<!ATTLIST Order sentinel CDATA 'LOADED_LOCAL_DTD'>");
        String original = "<!DOCTYPE Order SYSTEM '" + dtd.toUri() + "'><Order/>";
        Exchange exchange = exchangeWith(original);
        assertThrows(IllegalStateException.class, () -> esql.setBodyField(exchange, "Order.Status", "X"));
        assertEquals(original, exchange.getIn().getBody(String.class));
    }
}
