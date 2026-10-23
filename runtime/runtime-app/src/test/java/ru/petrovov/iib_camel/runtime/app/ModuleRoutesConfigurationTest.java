package ru.petrovov.iib_camel.runtime.app;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleRoutesConfigurationTest {
    @Test
    void moduleNamespacesPreventAccidentalRouteReplacement() throws Exception {
        var registered = new HashSet<String>();
        ModuleRoutesConfiguration.validateRouteIds("one", xml("one.receive"), registered);
        ModuleRoutesConfiguration.validateRouteIds("two", xml("two.receive"), registered);
        assertThrows(IllegalArgumentException.class,
                () -> ModuleRoutesConfiguration.validateRouteIds("one", xml("one.receive"), registered));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleRoutesConfiguration.validateRouteIds("one", xml("two.send"), registered));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleRoutesConfiguration.validateRouteIds("one", xml(""), registered));
    }

    private static String xml(String id) {
        return "<routes xmlns=\"http://camel.apache.org/schema/spring\"><route id=\"" + id
                + "\"><from uri=\"direct:input\"/><stop/></route></routes>";
    }
}
