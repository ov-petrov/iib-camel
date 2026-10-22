package com.example.iib_camel.runtime.compute;

import com.example.iib_camel.runtime.model.Adapter;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Скомпилированный артефакт IIB Compute-модуля Adapter_Compute1
 * (исходник: corpus corr-01, Adapter_Compute1.esql, конвертация FR-3.1;
 * ссылка из маршрута: esql://routine/adapter#Adapter_Compute1.Main).
 *
 * Поведение по ESQL:
 *   SET OutputRoot = InputRoot;                              — тело переносится как есть;
 *   SET OutputRoot.MQMD.ApplicationIdData = 'IIB-CAMEL-MIG'; — метка миграции (проверяется e2e).
 *
 * MQMD-метка отображается в свойство JMS-сообщения ApplicationIdData (см. тесты e2e).
 * Тело парсится в модель (FR-3.4); некорректный XML даёт исключение → doCatch → jms:ADAPTER.ERROR.Q.
 */
@Slf4j
@Component("adapterCompute1")
public class AdapterCompute1 {

    private final XmlMapper mapper = new XmlMapper();

    /** Аналог Compute Module Main(): парсит XML-тело и проставляет метку миграции. */
    public Adapter compute(Exchange exchange) throws Exception {
        String xml = exchange.getIn().getBody(String.class);
        Adapter adapter = mapper.readValue(xml, Adapter.class);
        exchange.getIn().setHeader("ApplicationIdData", "IIB-CAMEL-MIG");
        log.debug("Compute adapterCompute1: тело разобрано, проставлена метка IIB-CAMEL-MIG");
        return adapter;
    }
}
