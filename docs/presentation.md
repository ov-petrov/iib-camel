# Миграция с IBM IIB/ACE на Apache Camel: Сопоставление паттернов EIP

## Введение

Данный документ содержит сопоставление паттернов из книги **"Enterprise Integration Patterns" (Gregor Hohpe, Bobby Woolf)** с их реализациями в **IBM Integration Bus / App Connect Enterprise** и **Apache Camel**.

Цель — продемонстрировать, что Apache Camel обеспечивает **полное и нативное покрытие** всех ключевых интеграционных паттернов, описанных в EIP, и является обоснованным выбором для миграции.

---

## 1. Messaging Systems (Системы обмена сообщениями)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Message Channel** | Виртуальный канал, по которому приложения обмениваются сообщениями | MQ Queue / Topic, HTTP Input, встроенные transport-узлы | Endpoint / Component (ActiveMQ, Kafka, RabbitMQ, HTTP и 300+ компонентов). Канал задаётся декларативно через URI: `from("jms:queue:orders")` |
| **Message** | Атомарная единица данных, передаваемая по каналу | Message Tree (Headers, Properties, Body — `InputRoot`, `OutputRoot`) в формате logical tree | `Exchange`, содержащий `Message` с `Headers`, `Properties`, `Body`. Нативная поддержка type conversion |
| **Pipes and Filters** | Разбиение обработки на цепочку независимых фильтров, соединённых каналами | Message Flow — визуальный граф из соединённых узлов (nodes). Каждый узел — фильтр | `Route` — цепочка вызовов `.process()`, `.bean()`, `.transform()`, `.filter()`. Нативная реализация паттерна в DSL |
| **Message Router** | Направление сообщения в один из каналов на основании условия | Route Node, Filter Node, ESQL `IF/CASE` логика в Compute Node | `.choice().when().otherwise()` — декларативный DSL. Также `Dynamic Router`, `Routing Slip` |
| **Message Translator** | Преобразование формата сообщения из одного в другой | Mapping Node, ESQL-трансформации в Compute Node, Graphical Data Mapper, XSLT Node | `.transform()`, `.convertBodyTo()`, XSLT-компонент, Bean Binding, custom `Processor`. Поддержка DataFormat (JSON, XML, CSV, Protobuf и др.) |
| **Message Endpoint** | Код/компонент, через который приложение подключается к каналу | Input/Output Nodes (MQInput, HTTPInput, SOAPInput, FileInput и т.д.) | Component + Endpoint. URI-нотация: `from(...)` — consumer endpoint, `.to(...)` — producer endpoint |

---

## 2. Messaging Channels (Каналы обмена)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Point-to-Point Channel** | Канал, гарантирующий доставку ровно одному получателю | MQ Queue (через MQInput/MQOutput) | JMS Queue, SEDA, Direct, Kafka (single consumer group) |
| **Publish-Subscribe Channel** | Канал, доставляющий копию сообщения каждому подписчику | MQ Topic, Publication Node | JMS Topic, Kafka (multiple consumer groups), `seda:topic`, Google Pub/Sub и др. |
| **Datatype Channel** | Канал, по которому передаются сообщения строго определённого типа | Message Set / Message Model, привязанный к узлу (Validation Node) | Data Format + Contract/Validator. Можно задать `.validate()` или JSON Schema / XML Schema |
| **Dead Letter Channel** | Канал для сообщений, которые не удалось обработать | MQ Backout Queue (настройка `BOTHRESH`), Failure Terminal + Catch/Throw | `errorHandler(deadLetterChannel("jms:queue:DLQ"))` — встроенный паттерн первого класса |
| **Invalid Message Channel** | Отдельный канал для сообщений неверного формата | Failure Terminal → MQ Queue / File, ручная обработка в Compute Node | `.doTry().doCatch()` → маршрутизация в отдельный endpoint. `onException(ValidationException.class).to(...)` |
| **Guaranteed Delivery** | Гарантия доставки через персистентность | Persistent MQ Messages + транзакции (координация через MQ Transaction) | JMS Transactions, Kafka acks, Idempotent Consumer, JPA, надёжная доставка через транзакционные компоненты |
| **Channel Adapter** | Адаптер, соединяющий приложение (не поддерживающее обмен сообщениями) с каналом | Adapter Nodes (SAPInput, FileInput, FTPInput, JDBCInput, TCPIPInput и др.) | 300+ компонентов: `file:`, `ftp:`, `jdbc:`, `sap:`, `mail:`, `aws-s3:` и т.д. Один из сильнейших аспектов Camel |
| **Messaging Bridge** | Мост между разными системами обмена сообщениями | MQ-to-HTTP flow, Bridge между брокерами (вручную) | `from("activemq:queue:A").to("kafka:topicB")` — мост создаётся одной строкой |

---

## 3. Message Construction (Конструирование сообщений)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Command Message** | Сообщение как вызов команды/процедуры | Реализуется через MQ + Compute Node с бизнес-логикой | Bean Binding, `.to("bean:myService?method=execute")` |
| **Document Message** | Сообщение, содержащее данные (документ) для обработки | Основной тип — передача XML/JSON через message tree | Тело `Exchange` (`body`) — любой POJO, XML, JSON, byte[] |
| **Event Message** | Сообщение-уведомление о произошедшем событии | Publication Node + MQ Topic / JMS Topic | `from("kafka:events")`, Event-driven consumer, интеграция с EventBridge, Pub/Sub |
| **Request-Reply** | Пара сообщений: запрос и ответ на него | MQInput (ReplyToQ + CorrelId), HTTPInput/HTTPReply, SOAPInput/SOAPReply | `ExchangePattern.InOut`. `.to("jms:queue:request?replyTo=response")`, `requestBody()` в ProducerTemplate |
| **Return Address** | Адрес, по которому нужно отправить ответ, содержится в самом запросе | `ReplyToQ`, `ReplyToQMgr` в заголовках MQ-сообщения | JMS Header `JMSReplyTo`, HTTP callback URL в header |
| **Correlation Identifier** | Идентификатор для связи запроса с ответом | `MsgId` / `CorrelId` в MQ, ESQL-логика сопоставления | `JMSCorrelationID`, header-based correlation, `.setHeader("correlationId", ...)` |
| **Message Sequence** | Серия связанных сообщений с определённым порядком | MQ Message Groups (`GroupId`, `MsgSeqNumber`), ручная реализация в ESQL | Sequence header + Resequencer EIP, `.resequence()` |
| **Message Expiration** | Время жизни сообщения; после истечения — удаляется | MQ Expiry (`MQMD.Expiry`), HTTP timeout | `JMSExpiration`, TTL на уровне компонента, `.delay()` + `.timeout()` |

---

## 4. Message Routing (Маршрутизация сообщений)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Content-Based Router** | Направление сообщения по содержимому | Route Node (XPath/условие), Filter Node, ESQL `IF/CASE` в Compute Node | `.choice().when(xpath("/order/type = 'priority'")).to("direct:fast").otherwise().to("direct:normal")` |
| **Message Filter** | Отбрасывание сообщений, не соответствующих критерию | Filter Node, ESQL `RETURN FALSE`, Route Node без default | `.filter(header("type").isEqualTo("valid"))` |
| **Dynamic Router** | Маршрутизация, где следующий шаг определяется динамически в runtime | Compute Node с ESQL-логикой, задающей destination queue/URL | `.dynamicRouter(method(myBean, "route"))` — нативная поддержка |
| **Recipient List** | Отправка сообщения нескольким получателям, определяемым динамически | Compute Node, вычисляющий список адресатов + цикл отправки, или несколько Output-терминалов | `.recipientList(header("recipients"))` — одна строка |
| **Splitter** | Разбиение одного сообщения на несколько частей | ESQL-цикл + `PROPAGATE` в Compute Node. Также Java Compute Node с итерацией | `.split(body().tokenizeXML("order"))`, `.split(jsonpath("$.orders"))` |
| **Aggregator** | Сборка нескольких связанных сообщений в одно | Collector Node (ограниченный), Aggregation через ESQL + MQ (сложная ручная реализация) | `.aggregate(header("correlationId"), new MyAggregationStrategy()).completionSize(5)` — мощная встроенная поддержка |
| **Resequencer** | Упорядочивание сообщений, пришедших не по порядку | Resequence Node (существует, но ограничен), ESQL-ручная реализация | `.resequence(header("seqNum")).batch().size(100)` или `.stream()` |
| **Composed Message Processor** | Разбиение → обработка каждой части → сборка | Комбинация Compute (split) → Subflow → Compute (aggregate). Сложная реализация | `.split(...).process(...).end()` — автоматическая агрегация после split |
| **Scatter-Gather** | Рассылка запроса нескольким получателям, сбор ответов | Ручная реализация: Fan-out через несколько Output → Collector/Aggregation | `.multicast().to("direct:a", "direct:b").end()` с `AggregationStrategy`. Также `.recipientList()` + `.aggregate()` |
| **Routing Slip** | Список шагов обработки, прикреплённый к сообщению | Ручная реализация через ESQL: чтение маршрута из заголовка, динамическая маршрутизация | `.routingSlip(header("myRoutingSlip"))` — нативная поддержка |
| **Process Manager** | Центральный координатор, управляющий шагами обработки | Сложная ручная реализация: Compute Node + state management через DB/MQ | Через Camel Saga, StateMachine или интеграцию с workflow-engine (Camunda, Temporal) |
| **Message Broker** (паттерн) | Централизованный компонент маршрутизации, отделяющий отправителей от получателей | Сам IIB/ACE является реализацией этого паттерна | Camel context с набором routes выполняет роль lightweight message broker |
| **Throttler** | Ограничение скорости прохождения сообщений | Не встроен; реализуется через ESQL + timer или настройки Additional Instances | `.throttle(100).timePeriodMillis(10000)` — встроенный |
| **Load Balancer** | Распределение сообщений между несколькими получателями | Additional Instances (параллельные копии flow), внешний LB | `.loadBalance().roundRobin().to("direct:a", "direct:b")`. Стратегии: round-robin, random, failover, weighted, custom |
| **Multicast** | Отправка копии сообщения нескольким получателям одновременно | Несколько Output-терминалов из одного узла, Fan-out Node | `.multicast().to("direct:a", "direct:b", "direct:c")` |
| **Loop** | Повторная обработка сообщения заданное количество раз | ESQL `WHILE` + `PROPAGATE`, или рекурсивный subflow | `.loop(5).to("direct:process").end()` |
| **Wire Tap** | Отправка копии сообщения в побочный канал (аудит, логирование) без влияния на основной поток | Дублирование на Output Terminal + отдельная ветка flow | `.wireTap("jms:queue:audit")` — одна строка, асинхронно |

---

## 5. Message Transformation (Трансформация сообщений)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Envelope Wrapper** | Оборачивание сообщения в «конверт» (добавление заголовков, метаданных) | ESQL: построение SOAP Envelope, добавление MQMD/MQRFH2 заголовков | `.marshal()`, `.setHeader()`, SOAP DataFormat, custom Processor |
| **Content Enricher** | Дополнение сообщения данными из внешнего источника | Database Retrieve Node, HTTPRequest Node в середине flow, ESQL + DB `SELECT` | `.enrich("http://api/details", enrichStrategy)`, `.pollEnrich("file:data")` — нативный паттерн |
| **Content Filter** | Удаление из сообщения ненужных данных | ESQL `DELETE`, XSL Transform Node, Mapping Node | `.transform()`, `.removeHeaders()`, XSLT, `.process()` с фильтрацией |
| **Claim Check** | Сохранение данных во внешнее хранилище, замена на «квитанцию», и позднее извлечение | Ручная реализация: Compute Node → DB/File, далее retrieval | `.claimCheck(ClaimCheckOperation.Push)` / `.claimCheck(ClaimCheckOperation.Pop)` — встроенный |
| **Normalizer** | Приведение сообщений разных форматов к единому каноническому виду | Route Node (определение формата) + несколько Compute Node с ESQL-трансформациями | `.choice().when(isXml).to("direct:normalizeXml").when(isCsv).to("direct:normalizeCsv")` — комбинация CBR + Translator |
| **Canonical Data Model** | Единая модель данных, используемая как промежуточный формат | Message Model / Message Set. В ACE — DFDL-схемы, shared libraries | POJO, JSON Schema, Avro, Protobuf — любой формат, поддерживаемый DataFormat |

---

## 6. Messaging Endpoints (Конечные точки)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Polling Consumer** | Потребитель, периодически проверяющий канал на наличие сообщений | FileInput (polling directory), FTP/SFTP Input, DatabaseInput с polling interval | `from("file:inbox?delay=5000")`, `from("ftp:server?delay=60000")`, `from("sql:SELECT ...?delay=10000")` |
| **Event-Driven Consumer** | Потребитель, реагирующий на появление сообщения немедленно | MQInput (listener), HTTPInput, TCPIPInput — push-модель | `from("jms:queue:orders")`, `from("kafka:topic")`, `from("netty:tcp://...")` |
| **Competing Consumers** | Несколько потребителей на одной очереди для параллельной обработки | Additional Instances в Execution Group / Integration Server | `from("jms:queue:work?concurrentConsumers=10")`, Kubernetes scaling |
| **Message Dispatcher** | Распределитель, направляющий сообщения из одного канала определённым обработчикам | Label Node + Route Node, ESQL-dispatch | `.choice()` + `.to()`, Dynamic Router, Content-Based Router |
| **Selective Consumer** | Потребитель, выбирающий из канала только определённые сообщения | MQInput с Message Selector (JMS), ESQL-фильтрация на входе | JMS Selector: `from("jms:queue:orders?selector=type='VIP'")`, `.filter()` |
| **Durable Subscriber** | Подписчик, не теряющий сообщения при временном отключении | MQ Durable Subscription, настройка Subscription Name | `from("jms:topic:events?clientId=myId&durableSubscriptionName=sub1")`, Kafka consumer group |
| **Idempotent Consumer** | Гарантия однократной обработки сообщения (дедупликация) | Ручная реализация: Compute Node + DB-проверка по уникальному ID | `.idempotentConsumer(header("MessageId"), MemoryIdempotentRepository.memoryIdempotentRepository(200))` — встроенный |
| **Transactional Client** | Потребитель, обрабатывающий сообщения в рамках транзакции | MQ-координируемые транзакции (XA), настройка `Coordinated Transaction` на flow level | `.transacted()`, Spring Transaction Manager, JTA/XA поддержка |
| **Service Activator** | Компонент, вызывающий сервис в ответ на входящее сообщение | JavaCompute Node, .NET Compute Node, Compute Node с ESQL-вызовом | `.bean(myService, "processOrder")`, `.process(new MyProcessor())` |

---

## 7. System Management (Управление системой)

| EIP-паттерн | Описание | IBM IIB / ACE | Apache Camel |
|---|---|---|---|
| **Control Bus** | Механизм мониторинга и управления интеграционной системой | IBM Web UI, `mqsilist`, `mqsireportproperties`, административные MQ-очереди | ControlBus component: `to("controlbus:route?routeId=myRoute&action=status")`, JMX, Hawtio |
| **Detour** | Перенаправление сообщений через дополнительный маршрут (для диагностики) | Ручная реализация: переменная окружения + Route Node | `.choice().when(header("detour").isEqualTo(true)).to("direct:detourRoute")` или через ControlBus toggle |
| **Message Store** | Сохранение сообщений для аудита/отладки | Monitoring Nodes (Monitoring Event), Record & Replay, DB logging через Compute Node | `.to("jpa:messageLog")`, `.wireTap("file:audit")`, Camel Message History |
| **Message History** | Журнал прохождения сообщения через все этапы обработки | Activity Log, User Trace, `mqsireportflowstats` | Встроенный `Message History`: автоматически фиксирует все endpoints. `exchange.getProperty(Exchange.MESSAGE_HISTORY)` |
| **Smart Proxy** | Прокси, отслеживающий взаимосвязь запросов и ответов | Ручная реализация | Комбинация `.enrich()` + `.setHeader()` + корреляция |
| **Test Message** | Тестовое сообщение для проверки работоспособности системы | Flow Exerciser в Toolkit, Test Client | Camel Test Kit (`CamelTestSupport`), Mock endpoints: `mock:result`, `NotifyBuilder`, `AdviceWith` — мощнейший testing framework |
| **Channel Purger** | Очистка канала от ненужных сообщений | `CLEAR QLOCAL` (MQ), ручная очистка | `purge` через JMX, Browse component + selective delete |

---

## 8. Дополнительные паттерны

| Паттерн / Возможность | IBM IIB / ACE | Apache Camel |
|---|---|---|
| **Circuit Breaker** | Ручная реализация через timeout + retry logic | `.circuitBreaker()` (Resilience4j), `.onFallback()` |
| **Retry / Redelivery** | Backout Threshold (MQ), ручная реализация retry | `.errorHandler(defaultErrorHandler().maximumRedeliveries(5).redeliveryDelay(1000))` |
| **Saga (Long-running transaction)** | Ручная компенсирующая логика | `.saga()` — встроенная поддержка Saga EIP |
| **Service Registry** | Нет встроенного | Интеграция с Consul, Eureka, Kubernetes DNS |
| **Health Check** | `mqsilist`, admin REST API (ACE 12) | Camel Health Check API, Spring Actuator |

---

## 9. Сводная аргументация в пользу Apache Camel

### 9.1 Покрытие EIP-паттернов

| Критерий | IBM IIB / ACE | Apache Camel |
|---|---|---|
| Количество нативно реализованных EIP | ~60% (остальные — ручная реализация через ESQL/Java) | **~95%+** (нативные DSL-конструкции для каждого паттерна) |
| Способ реализации паттерна | Визуальный drag-and-drop + ESQL/Java код | Декларативный DSL (Java, XML, YAML, Kotlin, Groovy) |
| Расширяемость паттернов | Ограничена API узлов IBM | Полная: custom Processor, Component, DataFormat, Language |

### 9.2 Соответствие терминологии

Apache Camel **проектировался на основе книги EIP**. Названия классов, методов и DSL-конструкций напрямую соответствуют паттернам из книги. Это означает:

- **Низкий порог вхождения** для инженеров, знакомых с EIP
- **Самодокументируемый код** — route-определение читается как описание паттерна
- **Единый язык** между архитекторами, разработчиками и документацией

### 9.3 Сравнительная таблица по ключевым параметрам

| Параметр | IBM IIB / ACE | Apache Camel |
|---|---|---|
| **Лицензия** | Коммерческая (от $30K+ за PVU) | Apache 2.0 (бесплатная) |
| **Коннекторы** | ~40 встроенных адаптеров | **370+ компонентов** |
| **Язык трансформаций** | ESQL (проприетарный, IBM-only), Java | Java, Groovy, Kotlin, JavaScript, Simple, XPath, JSONPath и др. |
| **Тестирование** | Flow Exerciser (ручное), ограниченный JUnit | **CamelTestSupport**, Mock, AdviceWith, NotifyBuilder — полная автоматизация |
| **CI/CD** | Сложная интеграция: BAR-файлы, `mqsicreatebar`, проприетарные утилиты | Maven/Gradle, стандартный Java-проект, Docker-образы, Helm-чарты |
| **Cloud-native** | ACE в контейнерах (CP4I) — тяжёлый runtime (~1GB+) | Camel Quarkus: стартует за **~50ms**, образ **~50MB**; Spring Boot: стартует за **~2-5s** |
| **Kubernetes** | IBM Cloud Pak for Integration (дорогостоящая, тяжёлая) | Camel K — Kubernetes-native оператор, CRD-based, serverless-ready |
| **Community** | Закрытая экосистема IBM | **10K+ GitHub stars**, активное community, Stack Overflow |
| **Vendor Lock-in** | Высокий: ESQL, проприетарный toolkit, формат BAR | **Нулевой**: открытый стандарт, переносимость |
| **Разработчики на рынке** | Узкий пул специалистов, дорогие | Широкий пул Java-разработчиков |
| **Мониторинг** | IBM-специфичные инструменты | Micrometer, Prometheus, Grafana, OpenTelemetry, JMX |

### 9.4 Пример: один и тот же интеграционный сценарий

**Задача:** Получить сообщение из MQ-очереди, обогатить данными из REST API, маршрутизировать по типу, трансформировать в JSON, отправить в Kafka, при ошибке — в Dead Letter Queue.

#### IBM IIB / ACE

Требуется:

- Создать Message Flow (визуально)
- MQInput → Compute Node (ESQL для маршрутизации) → HTTPRequest Node (обогащение) → Route Node → ещё Compute Node (трансформация) → KafkaProducer Node
- Отдельная ветка Failure Terminal → MQOutput (DLQ)
- ESQL-код для трансформации: **50-200 строк**
- Настройка Configurable Service для HTTP, Kafka
- Сборка BAR-файла, деплой через `mqsideploy`

#### Apache Camel

```java
from("ibmmq:queue:ORDERS")
    .errorHandler(deadLetterChannel("ibmmq:queue:DLQ")
        .maximumRedeliveries(3)
        .redeliveryDelay(1000))
    .enrich("http://api.internal/customer/${header.customerId}", enrichStrategy)
    .choice()
        .when(jsonpath("$.type == 'PRIORITY'"))
            .marshal().json()
            .to("kafka:priority-orders")
        .when(jsonpath("$.type == 'STANDARD'"))
            .marshal().json()
            .to("kafka:standard-orders")
        .otherwise()
            .marshal().json()
            .to("kafka:other-orders")
    .end();
