package com.example.iib_camel.runtime.config;

import org.apache.camel.dsl.xml.io.CamelXmlRoutesBuilderLoader;
import org.apache.camel.spi.RoutesBuilderLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрация загрузчика XML-маршрутов для сгенерированных файлов
 * вида {@code Adapter.route.xml} (FR-R2.1).
 *
 * Camel определяет ключ лоадера как всё после ПЕРВОЙ точки в имени файла
 * (FileUtil.onlyExt): «Adapter.route.xml» → ключ «route.xml». Стандартный
 * {@link CamelXmlRoutesBuilderLoader} поддерживает только «xml», поэтому
 * регистрируем экземпляр с поддержкой ключа route.xml в Spring-реестре.
 */
@Configuration
public class CamelRoutesLoaderConfig {

    @Bean
    public RoutesBuilderLoader routeXmlLoader() {
        return new RouteXmlLoader();
    }

    /** XML-лоадер, поддерживающий имена файлов вида *.route.xml. */
    static final class RouteXmlLoader extends CamelXmlRoutesBuilderLoader {

        @Override
        public String getSupportedExtension() {
            return "route.xml";
        }
    }
}
