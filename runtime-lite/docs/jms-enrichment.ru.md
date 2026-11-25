# JMS-обогащение в runtime-lite

`EnrichingMessageProcessor<R>` обрабатывает документы, которым может понадобиться внешний JMS-вызов. За процессором
закреплена одна операция `operation()`. Для маршрута, который всегда формирует результат сразу, подходит
`MessageProcessor`. Имя Spring-бина процессора задаётся в `runtime-lite.routes.<id>.processor`.

Для очередей с SOAP 1.1 конверт обрабатывается инфраструктурой; все методы процессора и операции получают только XML
из Body. Настройка очередей, хранение конвертов и ограничения описаны в [SOAP на границе JMS](soap-transport.ru.md).

## Как обрабатывается документ

1. Сервис берёт входную очередь и имя Spring-бина из конфигурации маршрута. До вызова процессора он записывает
   тело, заголовки, очередь и имя бина в `inbound_message` по ключу `(route_id, message_id)`.
2. `prepareLookup()` возвращает готовое тело запроса `MessageBody` или `Optional.empty()`. При пропуске вызова
   сервис сразу передаёт в `map()` исходное сообщение и `EnrichmentResult.Skipped`. Для JMS-вызова он записывает
   состояние ожидания и ставит запрос в outbox. В обоих случаях запись о приёме и изменения до конца этого шага
   фиксируются одной транзакцией.
3. Outbox отправляет запрос по JMS. В `JMSReplyTo` указана очередь для ответа, в `JMSCorrelationID` — идентификатор
   запроса. Во время ожидания состояние хранится в PostgreSQL, а поток входного обработчика свободен. Внешний сервис
   отправляет ответ в очередь из `JMSReplyTo` и копирует `JMSCorrelationID`. По нему `runtime-lite` находит исходную
   обработку. Принятый ответ или установленная причина сбоя переводят её в `READY_TO_MAP`.
4. Воркер читает исходное сообщение из `inbound_message` по ключу `(route_id, message_id)`. Сохранённый ответ
   он разбирает через `decode()` операции. В `map(input, result)` поступает `EnrichmentResult.Success<R>`
   с разобранным ответом или `EnrichmentResult.Failure<R>` с кодом сбоя. Процессор возвращает одно сообщение
   и выбирает его транспорт и назначение. Сервис записывает его в outbox и завершает обработку одной транзакцией.

`JmsEnrichmentOperation<R>` связывает ключ операции с проверкой и разбором ответа типа `R`. Построение тела запроса
можно вынести в метод реализации операции, например `requestBody()`. Один бин операции можно внедрить в несколько
процессоров. Каждый процессор решает, как маршрутизировать исходное сообщение при сбое.

| Код в `EnrichmentResult.Failure` | Когда передаётся в `map()` |
| --- | --- |
| `DISPATCH_TIMEOUT` | Истёк срок отправки запроса. |
| `DISPATCH_FAILED` | Исчерпаны попытки отправки запроса. |
| `REPLY_TIMEOUT` | Ответ не поступил к сроку. |
| `INVALID_REPLY` | Тело ответа отсутствует, пустое или состоит из пробельных символов, ответ не прошёл проверку либо операция не смогла его разобрать. |
| `EXTERNAL_REJECTED` | Операция распознала отказ по протоколу и выбросила `ExternalEnrichmentRejectedException`. |

## Пример со справочником товаров

Если во входном XML указан `lookupCode`, процессор отправляет его в справочник и берёт название из ответа. Запрос
и ответ в примере передаются обычным текстом.

```xml
<Item>
    <code>A42</code>
    <label>Товар A42</label>
    <lookupCode>ADAPTER</lookupCode>
</Item>
```

При ответе `Адаптер` итоговый документ выглядит так.

```xml
<EnrichedItem>
    <code>A42</code>
    <label>Адаптер</label>
</EnrichedItem>
```

Если `lookupCode` отсутствует или содержит только пробелы, процессор сразу формирует тот же XML с исходным названием
`Товар A42`. При сбое процессор отправляет исходное тело и прикладные заголовки в `SAMPLE.ITEM.ERROR` и добавляет
код причины как свойство JMS `enrichment_failure_code`.

В `ItemLabelProcessor.java` объявлены два Spring-компонента. Операция описывает обмен со справочником. Процессор
проверяет, нужен ли запрос, и формирует итоговый документ.

```java
package ru.petrovov.iib_camel.runtime_lite.processor.example;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
final class ItemLabelOperation implements JmsEnrichmentOperation<String> {

    @Override
    public String key() {
        return "item-label-v1";
    }

    MessageBody requestBody(String code) {
        return MessageBody.text(code);
    }

    @Override
    public String decode(InboundMessage reply) {
        return reply.body();
    }
}

@Component("itemLabelProcessor")
@RequiredArgsConstructor
final class ItemLabelProcessor implements EnrichingMessageProcessor<String> {

    private static final String FAILURE_CODE_HEADER = "enrichment_failure_code";

    private final ItemLabelOperation lookup;
    private final XmlService xml;

    @Override
    public JmsEnrichmentOperation<String> operation() {
        return lookup;
    }

    @Override
    public Optional<MessageBody> prepareLookup(InboundMessage input) {
        var item = xml.read(input.body(), Item.class);

        return Optional.ofNullable(item.lookupCode)
            .filter(code -> !code.isBlank())
            .map(lookup::requestBody);
    }

    @Override
    public OutboundMessage map(InboundMessage input, EnrichmentResult<String> result) {
        return switch (result) {
            case EnrichmentResult.Skipped<String>() -> {
                var item = xml.read(input.body(), Item.class);
                yield enriched(item, item.label);
            }
            case EnrichmentResult.Success<String>(var label) -> enriched(xml.read(input.body(), Item.class), label);
            case EnrichmentResult.Failure<String>(var reason) -> error(input, reason);
        };
    }

    private OutboundMessage enriched(Item item, String label) {
        var enriched = new EnrichedItem(item.code, label);

        return OutboundMessage.builder()
            .transport(Transport.JMS)
            .destination("SAMPLE.ITEM.OUT")
            .payload(MessageBody.xml(xml.writeDocument(enriched)))
            .headers(Map.of())
            .build();
    }

    private OutboundMessage error(InboundMessage input, EnrichmentFailure reason) {
        var headers = new HashMap<>(input.headers());
        headers.put(FAILURE_CODE_HEADER, reason.name());
        return new OutboundMessage(Transport.JMS, "SAMPLE.ITEM.ERROR", input.body(), null, headers);
    }

    @XmlRootElement(name = "Item")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Item {

        private String code;
        private String label;
        private String lookupCode;
    }

    @XmlRootElement(name = "EnrichedItem")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"code", "label"})
    public static class EnrichedItem {

        private String code;
        private String label;

        public EnrichedItem() {
        }

        private EnrichedItem(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }
}
```

`prepareLookup()` возвращает готовый `MessageBody` запроса. Для XML используется `MessageBody.xml(...)`, для JSON
и обычного текста — `MessageBody.text(...)`.

В примере `xml.read(input.body(), Item.class)` проверяет корректность XML и читает его в JAXB DTO. Для проверки по XSD
можно использовать `xml.readValidated(input.body(), schemaLocation, Item.class)`. Схема должна допускать отсутствие
поля, по которому решается, нужен ли вызов. Для XML-ответа операция переопределяет `validateReply()` и возвращает
`MessageBody.xml(xml.validate(reply.body(), schemaLocation))`. `decode()` возвращает разобранный ответ.
Возврат `null` классифицируется как `INVALID_REPLY`. Отказ внешнего сервиса по протоколу можно выразить через
`ExternalEnrichmentRejectedException` в `validateReply()` или `decode()`.

### Настройки примера

В YAML `processor` совпадает с именем Spring-бина, а ключ `item-label-v1` — с результатом `lookup.key()`.
Имена очередей условные. Назначения `SAMPLE.ITEM.OUT` и `SAMPLE.ITEM.ERROR` задаёт процессор.

```yaml
runtime-lite:
  routes:
    items:
      input-queue: SAMPLE.ITEM.IN
      processor: itemLabelProcessor
  enrichment:
    operations:
      item-label-v1:
        request-queue: SAMPLE.LABEL.REQUEST
        reply-queue: SAMPLE.LABEL.REPLY
        reply-timeout: 15s
```

## Таймауты и работа нескольких экземпляров

| Параметр в `runtime-lite.enrichment` | По умолчанию | Что задаёт |
| --- | --- | --- |
| `dispatch-timeout` | `2m` | Сколько времени после постановки запроса в outbox можно начинать попытки отправки. |
| `default-reply-timeout` | `20s` | Сколько ждать ответа после подтверждённой отправки. `operations.<key>.reply-timeout` переопределяет это значение для операции. |
| `reply-concurrency` | `2` | Сколько потребителей запускается на каждую очередь ответов в одном экземпляре сервиса. |
| `poll-interval` | `1s` | Пауза после прохода планировщика, который возобновляет обработки и проверяет истёкшие сроки. |
| `batch-size` | `20` | Максимум записей за проход возобновления и за проверку каждого вида таймаута. |

При таймауте ответа обработка переходит в `READY_TO_MAP` с причиной `REPLY_TIMEOUT`. Поздние и повторные ответы
отбрасываются. Статус `READY_TO_MAP` учитывается как активный до фиксации результата. После рестарта обработку
можно продолжить, если доступны тот же процессор, версия операции и очередь ответа.

`COMPLETED` означает, что результат обогащения записан в outbox. При `EnrichmentResult.Failure` итоговый
статус — `FAILED`, а `failure_code` и `result_outbox_id` показывают причину сбоя и сообщение, выбранное процессором.
Если запись в БД откатывается, воркер повторно подбирает `READY_TO_MAP`. Если сам `map()` выбрасывает ошибку
преобразования, обработка завершается как `FAILED` без результата. Для сбоя внешнего вызова сохраняется его
исходный код, для ошибки преобразования успешного ответа записывается `MAPPING_FAILED`.

Реплики подключаются к общей БД и читают одни и те же настроенные очереди ответов. Каждая очередь ответов выделяется
этому сервису и может обслуживать несколько операций. При возобновлении обработки воркер блокирует запись через
`FOR UPDATE SKIP LOCKED`. Другие воркеры в это время могут взять следующие записи.

## Контракт с внешним сервисом

- Внешний сервис отправляет persistent JMS `TextMessage` в `JMSReplyTo` и переносит `JMSCorrelationID` без изменения.
- При повторной отправке запроса сохраняются `JMSCorrelationID` и `integration_message_id`. Для операции с побочными
  эффектами внешний сервис использует устойчивый ключ дедупликации и возвращает сохранённый результат при повторе.

## Эксплуатация

В `inbound_message` сохраняется вход для любого процессора, включая `MessageProcessor` и ветку без внешнего вызова.
Сервис берёт `source_destination` из `input-queue`, а `processor_id` из `processor`. Синтаксически корректный XML
записывается в `body_xml` типа `XML`, JSON, текст и некорректный XML в `body` типа `TEXT`, а заголовки в `headers`
типа `JSONB`. При определении формата XML сервис запрещает DTD и внешние сущности. Проверка по XSD относится
к логике процессора.

В `enrichment_workflow` хранятся состояние и ссылки на запись о приёме и записи outbox. Исходный документ
остаётся в `inbound_message` после успешного или неуспешного завершения обогащения. При откате транзакции приёма
запись аудита откатывается вместе с остальными изменениями. При очистке истории учитывайте связи таблиц и сроки
дедупликации.

Метрики Micrometer с префиксом `runtime.lite.enrichment` показывают исходы (`outcomes`), повторы отправки (`dispatch.retries`),
отброшенные ответы (`replies.discarded`), время отправки (`dispatch`), ожидание ответа (`reply.wait`), число активных
обработок (`active`) и возраст старейшей (`oldest.active`).

Разворачивайте новую версию на всех репликах до включения операции. Перед её отключением, сменой версии или удалением
очереди ответа дождитесь завершения активных обработок. Сетевые таймауты JMS-клиента действуют отдельно от таймаутов
обогащения. Для Artemis проверьте `callTimeout`, число попыток подключения и reconnect. При отсутствии конечного
`callFailoverTimeout` используется `callTimeout`. Для IBM MQ согласуйте `Connect_Timeout` и heartbeat, затем проверьте
поведение `JMSContext.commit()` при разрыве связи, точное эхо корреляции и отображение `JMSReplyTo` для используемого
формата сообщений. [Artemis Client Failover](https://artemis.apache.org/components/artemis/documentation/latest/client-failover.html) · [IBM MQ TCP stanza](https://www.ibm.com/docs/en/ibm-mq/9.3.x?topic=mqclientini-tcp-stanza-client-configuration-file) · [IBM MQ heartbeat](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=ccf-checking-that-other-end-channel-is-still-available)
