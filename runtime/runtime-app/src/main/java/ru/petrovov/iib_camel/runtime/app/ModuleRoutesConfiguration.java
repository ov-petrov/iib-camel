package ru.petrovov.iib_camel.runtime.app;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.NoErrorHandlerBuilder;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.xml.sax.InputSource;
import ru.petrovov.iib_camel.runtime.module.IntegrationModule;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class ModuleRoutesConfiguration {
    @Bean
    NoErrorHandlerBuilder runtimePropagateErrors() {
        return new NoErrorHandlerBuilder();
    }

    @Bean
    CamelContextConfiguration moduleRoutes(List<IntegrationModule> modules) {
        return new CamelContextConfiguration() {
            public void beforeApplicationStart(CamelContext context) {
                var ids = new HashSet<String>();
                var routeIds = new HashSet<String>();
                var resolver = new PathMatchingResourcePatternResolver();

                for (var module : modules) {
                    if (!ids.add(module.id())) {
                        throw new IllegalArgumentException("Duplicate module id: " + module.id());
                    }

                    try {
                        var resources = resolver.getResources(module.routesPattern());
                        if (resources.length == 0) {
                            throw new IllegalArgumentException("No routes found for module: " + module.id());
                        }

                        for (var resource : resources) {
                            try (var input = resource.getInputStream()) {
                                String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                validateRouteIds(module.id(), xml, routeIds);
                                PluginHelper.getRoutesLoader(context).loadRoutes(
                                    ResourceHelper.fromString(module.id() + "-" + resource.getFilename(), xml));
                            }
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException("Cannot load module: " + module.id(), failure);
                    }
                }
            }

            public void afterApplicationStart(CamelContext context) {
            }
        };
    }

    static void validateRouteIds(String moduleId, String xml, Set<String> registered) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var routes = document.getElementsByTagNameNS("http://camel.apache.org/schema/spring", "route");
        if (routes.getLength() == 0) {
            throw new IllegalArgumentException("Module XML must declare routes");
        }

        for (int index = 0; index < routes.getLength(); index++) {
            String id = routes.item(index).getAttributes().getNamedItem("id") == null
                ? ""
                    : routes.item(index).getAttributes().getNamedItem("id").getNodeValue();
            if (!id.startsWith(moduleId + ".") || !registered.add(id)) {
                throw new IllegalArgumentException("Route id must be unique and start with " + moduleId + ".: " + id);
            }
        }
    }
}
