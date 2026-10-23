package ru.petrovov.iib_camel.runtime.compute;

import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.petrovov.iib_camel.converter.esql.EsqlParser;
import ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator;

import static org.junit.jupiter.api.Assertions.*;

class EsqlPilotExecutionTest {
    @TempDir Path directory;

    @Test
    void freshBeanExecutesIfElseAndReadsStableInputAndCurrentOutput() throws Exception {
        AbstractComputeBean bean = compile("""
                SET OutputRoot = InputRoot;
                SET OutputRoot.XMLNSC.Order.Amount = '0';
                IF CAST(InputRoot.XMLNSC.Order.Amount AS DECIMAL) >= 10.5 AND NOT FALSE THEN
                  SET OutputRoot.XMLNSC.Order.Status = 'HIGH';
                ELSEIF InputRoot.XMLNSC.Order.Amount IS NULL THEN
                  SET OutputRoot.XMLNSC.Order.Status = 'MISSING';
                ELSE
                  IF TRUE THEN SET OutputRoot.XMLNSC.Order.Status = 'LOW'; END IF;
                END IF;
                SET OutputRoot.XMLNSC.Order.Seen = OutputRoot.XMLNSC.Order.Status;
                """);
        for (String amount : List.of("12", "3", "missing")) {
            Exchange exchange = exchange(amount.equals("missing") ? "<Order/>" : "<Order><Amount>" + amount + "</Amount></Order>");
            bean.compute(exchange);
            String expected = amount.equals("12") ? "HIGH" : amount.equals("3") ? "LOW" : "MISSING";
            assertEquals("<Order><Amount>0</Amount><Status>" + expected + "</Status><Seen>" + expected + "</Seen></Order>",
                    exchange.getIn().getBody());
        }
    }

    @Test
    void freshBeanCreatesOutputInsteadOfImplicitCopyAndKeepsScalarTypes() throws Exception {
        AbstractComputeBean bean = compile("""
                SET OutputRoot.XMLNSC.Result.Value = InputRoot.XMLNSC.Order.Value;
                SET OutputRoot.MQMD.IntegerValue = CAST('0012' AS INTEGER);
                SET OutputRoot.MQMD.DecimalValue = CAST('12.50' AS DECIMAL);
                SET OutputRoot.MQMD.Flag = CAST('TRUE' AS BOOLEAN);
                """);
        Exchange exchange = exchange("<Order><Value>source</Value><Untouched>lost</Untouched></Order>");
        exchange.getIn().setHeader("InputOnly", "lost");
        bean.compute(exchange);
        assertEquals("<Result><Value>source</Value></Result>", exchange.getIn().getBody());
        assertNull(exchange.getIn().getHeader("InputOnly"));
        assertEquals(12L, exchange.getIn().getHeader("IntegerValue"));
        assertEquals(new java.math.BigDecimal("12.50"), exchange.getIn().getHeader("DecimalValue"));
        assertEquals(Boolean.TRUE, exchange.getIn().getHeader("Flag"));
    }

    @Test
    void freshBeanDeletesNullAndDistinguishesEmptyMissingAndUnknown() throws Exception {
        AbstractComputeBean bean = compile("""
                SET OutputRoot = InputRoot;
                SET OutputRoot.XMLNSC.Order.Delete = NULL;
                SET OutputRoot.MQMD.Remove = CAST(NULL AS CHARACTER);
                IF InputRoot.XMLNSC.Order.Missing = 'x' THEN
                  SET OutputRoot.MQMD.Bad = 'bad';
                ELSE
                  SET OutputRoot.MQMD.UnknownBranch = 'else';
                END IF;
                IF InputRoot.XMLNSC.Order.Empty = '' AND InputRoot.XMLNSC.Order.Missing IS NULL THEN
                  SET OutputRoot.MQMD.EmptyIsPresent = TRUE;
                END IF;
                IF FALSE OR NULL THEN SET OutputRoot.MQMD.Bad = 'bad'; END IF;
                IF TRUE AND NULL THEN SET OutputRoot.MQMD.Bad = 'bad'; END IF;
                IF NOT NULL THEN SET OutputRoot.MQMD.Bad = 'bad'; END IF;
                IF TRUE OR CAST(InputRoot.XMLNSC.Order.Empty AS INTEGER) = 1 THEN
                  SET OutputRoot.MQMD.ShortCircuit = TRUE;
                END IF;
                IF FALSE AND CAST(InputRoot.XMLNSC.Order.Empty AS INTEGER) = 1 THEN
                  SET OutputRoot.MQMD.Bad = 'bad';
                END IF;
                """);
        Exchange exchange = exchange("<Order><Delete>x</Delete><Empty/></Order>");
        exchange.getIn().setHeader("Remove", "gone");
        bean.compute(exchange);
        assertEquals("<Order><Empty/></Order>", exchange.getIn().getBody());
        assertNull(exchange.getIn().getHeader("Remove"));
        assertNull(exchange.getIn().getHeader("Bad"));
        assertEquals("else", exchange.getIn().getHeader("UnknownBranch"));
        assertEquals(Boolean.TRUE, exchange.getIn().getHeader("EmptyIsPresent"));
        assertEquals(Boolean.TRUE, exchange.getIn().getHeader("ShortCircuit"));
    }

    @Test
    void dynamicInvalidCastThrowsAndRestoresInput() throws Exception {
        AbstractComputeBean bean = compile("""
                SET OutputRoot.XMLNSC.Result.Before = 'changed';
                SET OutputRoot.MQMD.Value = CAST(InputRoot.XMLNSC.Order.Value AS INTEGER);
                """);
        Exchange exchange = exchange("<Order><Value>12x</Value></Order>");
        exchange.getIn().setHeader("Original", "kept");
        assertThrows(IllegalArgumentException.class, () -> bean.compute(exchange));
        assertEquals("<Order><Value>12x</Value></Order>", exchange.getIn().getBody());
        assertEquals("kept", exchange.getIn().getHeader("Original"));
    }

    @Test
    void invalidLiteralCastAndUnknownNestedLogicCannotClaimComplete() throws Exception {
        for (String body : List.of("SET OutputRoot.MQMD.Value = CAST('12x' AS INTEGER);",
                "IF TRUE THEN SET OutputRoot.MQMD.Value = 'before'; ELSE WHILE TRUE DO SET OutputRoot.MQMD.Value = 'bad'; END WHILE; END IF;",
                "IF TRUE THEN SET OutputRoot.MQMD.Value = 'before'; ELSE MYSTERY; END IF;")) {
            var result = generate(body);
            assertFalse(result.complete(), body);
            AbstractComputeBean bean = compileResult(result);
            Exchange exchange = exchange("<Order/>");
            assertThrows(UnsupportedOperationException.class, () -> bean.compute(exchange));
            assertEquals("<Order/>", exchange.getIn().getBody());
        }
    }

    private AbstractComputeBean compile(String body) throws Exception {
        var result = generate(body);
        assertTrue(result.complete(), result.issues().toString());
        return compileResult(result);
    }
    private static EsqlToJavaGenerator.BeanResult generate(String body) {
        return new EsqlToJavaGenerator().generate(new EsqlParser().parse("CREATE COMPUTE MODULE Pilot "
                + "CREATE FUNCTION Main() RETURNS BOOLEAN BEGIN " + body + " RETURN TRUE; END; END MODULE;"));
    }
    private AbstractComputeBean compileResult(EsqlToJavaGenerator.BeanResult result) throws Exception {
        Path target = Files.createTempDirectory(directory, "fresh-");
        Path source = target.resolve(result.className() + ".java");
        Files.writeString(source, result.java());
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-classpath", System.getProperty("java.class.path"), "-proc:none", "-d", target.toString(), source.toString()),
                diagnostics.toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{target.toUri().toURL()}, getClass().getClassLoader())) {
            return (AbstractComputeBean) loader.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + "." + result.className()).getConstructor().newInstance();
        }
    }
    private static Exchange exchange(String body) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(body);
        return exchange;
    }
}
