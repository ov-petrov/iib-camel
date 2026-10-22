package ru.petrovov.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsqlGeneratedExecutionTest {

    @TempDir
    Path directory;

    @Test
    void incompleteModulesThrowBeforeCallingAnyBusinessHelper() throws Exception {
        List<String> unsupported = List.of(
                "IF TRUE THEN SET OutputRoot.MQMD.A = 'A'; ELSE SET OutputRoot.MQMD.B = 'B'; END IF;",
                "CASE TRUE WHEN TRUE THEN SET OutputRoot.MQMD.A = 'A'; ELSE SET OutputRoot.MQMD.B = 'B'; END CASE;",
                "WHILE TRUE DO SET OutputRoot.MQMD.A = 'A'; END WHILE;",
                "RETURN FALSE;", "PROPAGATE;", "DECLARE flag BOOLEAN TRUE;",
                "SET OutputRoot.MQMD.Value = 'a' || 'b';", "CALL Helper('*/');", "THROW USERMESSAGE 'fail';");
        List<EsqlToJavaGenerator.BeanResult> beans = new ArrayList<>();
        for (int i = 0; i < unsupported.size(); i++) {
            var bean = bean("Example" + i, "SET OutputRoot = InputRoot;\n"
                    + "SET OutputRoot.MQMD.Before = 'before';\n" + unsupported.get(i) + "\nRETURN TRUE;");
            assertFalse(bean.complete());
            assertFalse(bean.issues().isEmpty());
            assertTrue(bean.statements().size() >= 2, "пооператорная диагностика сохранена");
            beans.add(bean);
        }
        try (URLClassLoader loader = compile(beans)) {
            Class<?> exchange = loader.loadClass("org.apache.camel.Exchange");
            for (var bean : beans) {
                Class<?> type = loader.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + "." + bean.className());
                Object instance = type.getConstructor().newInstance();
                var failure = assertThrows(InvocationTargetException.class,
                        () -> type.getMethod("compute", exchange).invoke(instance, new Object[] {null}));
                assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
                assertEquals(0, type.getField("operations").get(instance), bean.className());
            }
        }
    }

    @Test
    void supportedBeanCompilesAndPreservesLiteralContent() throws Exception {
        String literal = "it's; first\nsecond\\tail\"quoted\"";
        var bean = bean("Literal", "SET OutputRoot = InputRoot;\n"
                + "SET OutputRoot.MQMD.CommentField = 'it''s; first\nsecond\\tail\"quoted\"';\nRETURN TRUE;");
        assertTrue(bean.complete());
        try (URLClassLoader loader = compile(List.of(bean))) {
            Class<?> type = loader.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + ".Literal");
            Object instance = type.getConstructor().newInstance();
            type.getMethod("compute", loader.loadClass("org.apache.camel.Exchange"))
                    .invoke(instance, new Object[] {null});
            assertEquals(2, type.getField("operations").get(instance));
            assertEquals(literal, type.getField("lastValue").get(instance));
        }
    }

    private static EsqlToJavaGenerator.BeanResult bean(String name, String body) {
        return new EsqlToJavaGenerator().generate(new EsqlParser().parse("CREATE COMPUTE MODULE " + name
                + " CREATE FUNCTION Main() RETURNS BOOLEAN BEGIN\n" + body + "\nEND; END MODULE;"));
    }

    private URLClassLoader compile(List<EsqlToJavaGenerator.BeanResult> beans) throws Exception {
        List<String> args = new ArrayList<>(List.of("-d", directory.toString(), "-proc:none"));
        for (var bean : beans) {
            Path source = directory.resolve(bean.className() + ".java");
            Files.writeString(source, bean.java());
            args.add(source.toString());
        }
        Path exchange = directory.resolve("Exchange.java");
        Files.writeString(exchange, "package org.apache.camel; public interface Exchange {}");
        Path component = directory.resolve("Component.java");
        Files.writeString(component, "package org.springframework.stereotype; public @interface Component { String value(); }");
        Path base = directory.resolve("AbstractComputeBean.java");
        Files.writeString(base, """
                package ru.petrovov.iib_camel.runtime.compute;
                import org.apache.camel.Exchange;
                public abstract class AbstractComputeBean {
                    public int operations;
                    public String lastValue;
                    public void compute(Exchange exchange) { main(exchange); }
                    protected abstract void main(Exchange exchange);
                    protected void copy(Exchange exchange) { operations++; }
                    protected void mqmd(Exchange exchange, String field, String value) { operations++; lastValue = value; }
                    protected void setBodyField(Exchange exchange, String path, String value) { operations++; lastValue = value; }
                }
                """);
        args.addAll(List.of(exchange.toString(), component.toString(), base.toString()));
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                args.toArray(String[]::new)), diagnostics.toString());
        return new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
    }
}
