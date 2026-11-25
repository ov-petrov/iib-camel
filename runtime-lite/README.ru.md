# Runtime Lite

`runtime-lite` — отдельное JMS-приложение на Java 21, Spring Boot 4.1.2 и PostgreSQL. Оно читает JMS-сообщения, вызывает
Java-обработчик и надёжно записывает исходящие сообщения в outbox. Это независимый Maven-проект: он не использует Camel,
не является модулем конвертера и не получает его результат автоматически.

## Структура и обработчик

- `inbox` принимает JMS-сообщения, сохраняет входящие документы для аудита и обрабатывает их в транзакции БД.
- `processor` содержит контракты `MessageProcessor` и `EnrichingMessageProcessor`.
- `enrichment` хранит состояние JMS-обогащения, принимает ответы и возобновляет обработку.
- `outbox` хранит исходящие сообщения и отправляет их в JMS или Kafka.
- `transport` принимает и отправляет JMS `TextMessage`, ограничивает размер тела и отправляет текст в Kafka.
- `config` содержит общие конфигурации, включая `config.transport`, `config.inbox` и `config.xml`.
- `jdbc` и `xml` содержат поддержку работы с таблицами доставки и защищённую обработку XML.

Для маршрута без внешнего вызова зарегистрируйте бин `MessageProcessor`. Укажите его имя в
`runtime-lite.routes.<id>.processor`.

```java
@Component("sampleProcessor")
public class SampleProcessor implements MessageProcessor {
    @Override
    public OutboundMessage process(InboundMessage message) {
        return OutboundMessage.of(Transport.JMS, "SAMPLE.OUT", message.body());
    }
}
```

Обработчик возвращает `OutboundMessage` с транспортом, конкретным именем очереди или топика и телом. Для сообщения
с ключом Kafka и заголовками используйте `new OutboundMessage(transport, destination, body, key, headers)`;
фабрика `of(transport, destination, body)` создаёт сообщение без ключа и заголовков. Для дополнительной отправки
внедрите `MessagePublisher` и вызовите, например, `send(OutboundMessage.of(Transport.KAFKA, "sample.audit", body))`
внутри `process`. Возвращаемое и дополнительные сообщения попадают в одну транзакционную outbox; publisher не
отправляет их синхронно в брокер. Outbox сохраняет выбранные обработчиком транспорт и имя назначения и использует
их при повторной доставке.

В обработчик можно внедрять обычные Spring-сервисы, репозитории Spring Data JDBC и `XmlService`. Для
преобразования моделей доступны MapStruct и Lombok. Их совместная обработка аннотаций настроена в Maven. Компоненты
размещайте внутри пакета `ru.petrovov.iib_camel.runtime_lite` или явно добавьте свои пакеты в сканирование Spring.

## Асинхронное JMS-обогащение

У `EnrichingMessageProcessor<R>` одна фиксированная операция `operation()`. По её ключу в YAML задаются очереди и
срок ожидания ответа. `prepareLookup()` решает по входному документу, нужен ли вызов.

Метод `map(input, result)` получает исходное сообщение и один из вариантов `EnrichmentResult<R>`.

- `Skipped` — `prepareLookup()` вернул `Optional.empty()`, поэтому процессор сразу формирует результат.
- `Success` — операция разобрала ответ внешнего сервиса и вернула значение типа `R`.
- `Failure` — внешний вызов завершился сбоем. Процессор получает код причины и может направить исходное сообщение
  в очередь ошибок.

После внешнего вызова воркер загружает исходный документ из `inbound_message`. Сервис в одной транзакции записывает
сообщение из `map()` в outbox и завершает обработку. Для `Failure` он сохраняет статус `FAILED`, код сбоя и ссылку
на сообщение в outbox.

[Пример процессора, настройки JMS и таймауты](docs/jms-enrichment.ru.md)

## Работа с XML и XSD

Spring предоставляет `XmlService` для определения корня документа и преобразования между XML и Jakarta XML Binding
(JAXB) DTO. Он работает с ручными и сгенерированными JAXB-классами. Проверка по XSD включается явно через
`readValidated()`, `writeValidatedDocument()` или `validate(text, schemaLocation)`. Схемы задаются путём
`classpath:` и компилируются с повторным использованием. Ниже пример внутри `MessageProcessor`.

```java
QName root = xmlService.detectRoot(message.body());

SampleRequest request = xmlService.readValidated(
    message.body(),
    "classpath:schemas/sample/request.xsd",
    SampleRequest.class
);

SampleResponse response = handle(request);
ValidatedXml output = xmlService.writeValidatedDocument(response, "classpath:schemas/sample/response.xsd");
return OutboundMessage.of(Transport.JMS, "SAMPLE.OUT", MessageBody.xml(output), null, Map.of());
```

`XmlService` внедряется как Spring-зависимость. DTO, схемы и метод `handle` определяются конкретной интеграцией.
Для формата без XSD используются `read(text, Dto.class)` и `write(dto)`. Для ручного DTO задайте корень через
`@XmlRootElement`. При чтении сгенерированного DTO сервис учитывает `ObjectFactory` из его пакета. Если корневой
элемент при записи задаёт фабрика, передайте в `write()` или `writeValidatedDocument()` созданный ею `JAXBElement`.

`detectRoot()` возвращает `QName` с локальным именем и URI пространства имён. `read()` проверяет синтаксис
документа и читает его по JAXB-привязкам. Для проверки всего контракта используйте вызов с XSD. Проверка схемы и
чтение DTO выполняются отдельно.

Сервис запрещает DTD и внешние XML-сущности. XSD загружается из classpath, а разрешённые локальные зависимости
должны находиться рядом с корневой схемой или в её подкаталогах. Ошибка чтения или обязательной проверки документа
может быть обработана процессором. Исключение из процессора на этапе приёма откатывает транзакцию. Дальнейшая
повторная доставка зависит от настроек брокера.

Jackson используется для JSON, в том числе для заголовков в колонках `JSONB`.

## Генерация DTO и примеров XML из XSD

`xsd-tool` генерирует Java DTO с аннотациями Jakarta XML Binding и примеры XML, проверенные по исходной XSD.
[README модуля xsd-tool](xsd-tool/README.ru.md)

## Конфигурация

Минимальный пример использует только синтетические идентификаторы:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/runtime_lite
    username: runtime_lite
    password: ${LITE_DB_PASSWORD}
    hikari:
      schema: ${spring.liquibase.default-schema}
  liquibase:
    default-schema: ${LITE_DB_SCHEMA:runtime_lite}
  kafka:
    bootstrap-servers: ${LITE_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      properties:
        '[enable.idempotence]': true
        '[delivery.timeout.ms]': 30000
        '[request.timeout.ms]': 30000
        '[max.block.ms]': 30000
        '[linger.ms]': 0

runtime-lite:
  jms:
    provider: ${LITE_JMS_PROVIDER:IBM_MQ}
  mq:
    host: localhost
    port: 1414
    queue-manager: QM1
    channel: DEV.APP.SVRCONN
    username: ${LITE_MQ_USER:}
    password: ${LITE_MQ_PASSWORD:}
  artemis:
    broker-url: ${LITE_ARTEMIS_URL:tcp://localhost:61616}
    username: ${LITE_ARTEMIS_USER:}
    password: ${LITE_ARTEMIS_PASSWORD:}
  listeners:
    auto-startup: true
    max-body-bytes: 10485760
  outbox:
    batch-size: 20
    max-attempts: 10
    retry-delay: 10s
    poll-interval: 1s
  routes:
    sample:
      input-queue: SAMPLE.IN
      processor: sampleProcessor
      concurrency: 1
```

`runtime-lite.jms.provider` выбирает штатный JMS-провайдер: `IBM_MQ` или `ARTEMIS`. По умолчанию используется IBM MQ;
для Artemis установите `LITE_JMS_PROVIDER=ARTEMIS`. Подключение Artemis задаётся через `LITE_ARTEMIS_URL`,
`LITE_ARTEMIS_USER` и `LITE_ARTEMIS_PASSWORD`; имя пользователя и пароль задаются парой. Для IBM MQ используются
настройки `runtime-lite.mq` и переменные `LITE_MQ_*`.

Передавайте `LITE_DB_PASSWORD` и учётные данные выбранного брокера через секрет-хранилище окружения. Не помещайте
секреты в YAML или репозиторий. IBM MQ принимает пару username/password; TLS настраивается `cipher-suite` и при
необходимости `peer-name`. Kafka настраивается стандартными свойствами `spring.kafka`: общие параметры безопасности
можно указать в `spring.kafka.properties` или через соответствующие настройки `spring.kafka.ssl` и
`spring.kafka.security`. Настройки производителя задаются в `spring.kafka.producer`. Входное тело ограничено
`max-body-bytes`.

Значения производителя в YAML можно изменить: приложение не перезаписывает их в Java-коде. `acks: all` требует
подтверждения от всех синхронизированных реплик согласно настройкам брокера, а `enable.idempotence` защищает от
дубликатов при повторных отправках внутри Kafka producer. Ожидание результата отправки завершается по результату
Kafka-клиента; отдельного таймаута ожидания future нет. `max.block.ms` ограничивает блокировку при вызове отправки,
а `delivery.timeout.ms`, `request.timeout.ms` и `linger.ms` управляют сроками доставки и запросов. Kafka требует,
чтобы `delivery.timeout.ms` был не меньше суммы `request.timeout.ms` и `linger.ms`. Изменение этих настроек может
ослабить гарантии доставки. Даже подтверждённое сообщение может быть отправлено повторно из outbox, поэтому
потребителю нужна дедупликация по `integration_message_id`.

## Проверки состояния

Spring Boot Actuator предоставляет HTTP-проверки состояния приложения. Приложение использует зависимости
`spring-boot-starter-actuator` и `spring-boot-starter-webmvc`; пробы включены явно. По умолчанию HTTP-сервер
слушает порт `8080`. Его можно изменить стандартной настройкой `server.port` или переменной `SERVER_PORT`.
Доступен только endpoint `health`, подробности состояния в ответе не показываются. Аутентификация HTTP-запросов
не настроена: доступ к порту следует ограничить сетевыми правилами окружения.

```sh
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
```

Проверки отражают стандартные состояния Spring Application Availability. Liveness показывает, может ли приложение
продолжать работу; readiness — готовность приложения. По умолчанию readiness не проверяет подключение к БД, MQ или
Kafka и работу JMS listener. Она также не управляет потреблением JMS: состояние readiness в Kubernetes само по себе
не гарантирует остановку потребления сообщений.

Пример проверок контейнера в Kubernetes:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
```

Настройте задержки и пороги проверок с учётом времени запуска приложения и условий окружения.

## Сборка и доставка

Требуется JDK 21 и Maven 3.9+:

```sh
mvn -B -f runtime-lite/pom.xml verify
```

Чтобы включить интеграционные тесты, запустите `mvn -B -f runtime-lite/pom.xml -Pint-test verify`. Они используют
один файл настроек. Для полного прогона достаточно PostgreSQL и Artemis. Сквозные тесты проверяют обычную обработку
и JMS-обогащение в отдельных схемах и очередях. [Переменные, команды запуска и наблюдение за сообщениями](docs/integration-tests.ru.md)

После добавления обработчика и настроек запустите собранное приложение:

```sh
java -jar runtime-lite/target/iib-camel-runtime-lite-0.0.1-SNAPSHOT.jar
```

`spring.liquibase.default-schema` задаёт общую схему PostgreSQL для приложения; по умолчанию это `runtime_lite`.
Её можно указать через `LITE_DB_SCHEMA` или стандартную настройку Spring. Пул Hikari устанавливает эту же схему
для каждого соединения, поэтому обычные запросы без имени схемы и Spring Data JDBC используют её по умолчанию.
Служебные запросы inbox/outbox дополнительно указывают схему явно. Liquibase создаёт в ней свои таблицы истории и
блокировки, а также `inbound_message`, `outbox_message` и `enrichment_workflow`. Явно указанная в SQL или отображении
сущности другая схема сохраняет своё значение.

SQL для Liquibase хранится в `src/main/resources/db/changelog/sql`. У каждой таблицы в `tables` свой SQL-файл с её
индексами и свой changeset с `runOnChange: true`. Функции и последовательности размещаются в `functions` и `sequences`
соответственно. Начальное наполнение выносится в `dictionaries` отдельным файлом с `INSERT` для каждого справочника.
`db.changelog-master.yaml` задаёт порядок применения файлов.
`CREATE ... IF NOT EXISTS` пропускает уже созданный объект. Изменение существующей таблицы задавайте через `ALTER TABLE`,
а изменение определения индекса через явное пересоздание.

Временные поля таблиц доставки имеют тип `TIMESTAMPTZ`. Начальные метки и проверки сроков используют
`clock_timestamp()`. Сроки ожидания и повторной отправки рассчитываются с дробной частью секунды.

Сервис создаёт схему при запуске, если её ещё нет; пользователю БД нужны необходимые права. Имя должно начинаться со
строчной латинской буквы или `_` и содержать не более 63 строчных латинских букв, цифр и `_`. Не переопределяйте
`spring.datasource.hikari.schema` или `spring.liquibase.liquibase-schema` отдельно: при несовпадении приложение не
запустится. Смена схемы не переносит данные из прежней схемы.

Входной JMS message ID и ID маршрута образуют ключ идемпотентности. До вызова любого процессора сервис записывает
в `inbound_message` исходное тело и заголовки. Поля `source_destination` и `processor_id` содержат имя входной очереди
и имя Spring-бина из маршрута. Синтаксически корректный XML сохраняется в `body_xml` типа `XML`. JSON, текст и
некорректный XML сохраняются в `body` типа `TEXT`. Проверку по XSD выполняет процессор.

Входная запись, бизнес-изменения обработчика и записи outbox, созданные при приёме, фиксируются одной транзакцией
PostgreSQL. При её откате запись аудита тоже откатывается, а повторную доставку определяют настройки брокера.
JMS listener подтверждает приём в отдельной JMS-транзакции после фиксации БД. Запись входа сохраняется после
завершения или сбоя обогащения. Переименование маршрута меняет ключ дедупликации, поэтому учитывайте уже
полученные сообщения.

Планировщик забирает PENDING записи с блокировкой `SKIP LOCKED`, отправляет их с подтверждением транспорта и затем
отмечает SENT. Сбой после отправки, но до отметки SENT может вызвать повторную отправку; отправка имеет семантику
at-least-once, а после `max-attempts` запись требует ручного восстановления. Потребителям следует дедуплицировать по
передаваемому заголовку Kafka или свойству JMS `integration_message_id`. При ошибке запись повторяется после `retry-delay`, пока число попыток не достигнет
`max-attempts`; затем статус становится FAILED и запись больше не выбирается. Автоматическая очистка записей не
реализована. Оператор может проверить `last_error` и число попыток, устранить причину и вручную вернуть запись в
PENDING, сохранив её содержимое, например:

```sql
UPDATE runtime_lite.outbox_message
SET status = 'PENDING', available_at = clock_timestamp(), last_error = NULL
WHERE id = '00000000-0000-4000-8000-000000000001' AND status = 'FAILED' AND jms_reply_to IS NULL;
```

Укажите фактическую схему и UUID записи при восстановлении. Изменение статуса не обнуляет счётчик attempts: после
следующей ошибки запись снова перейдёт в FAILED. Для полного нового цикла повторов можно также установить `attempts = 0`.
Перед повторным запуском убедитесь, что причина ошибки устранена.

Порядок доставки между сообщениями не гарантируется: несколько экземпляров могут отправлять записи параллельно.
Записи содержат тело и заголовки сообщений; настройте доступ к БД и срок их хранения с учётом данных приложения.

## Логирование

Логи выводятся только в консоль. Профиль `int-test` использует обычный текстовый формат,
иначе — JSON (Logstash). На уровне INFO записываются тело полученного сообщения и информация о сохранении сообщения
в outbox после фиксации транзакции. Технические подробности обработки и отправки доступны на уровне DEBUG;
ошибки доставки отражаются на уровне WARN, а подробности исключений — на DEBUG. Тела сообщений могут содержать
конфиденциальные данные: учитывайте это при настройке доступа к журналам и срока их хранения.

## Хранение тел сообщений

JMS передаёт `TextMessage`, а Kafka использует `StringSerializer`. При записи в PostgreSQL `MessageBody.xml(...)`
помещает проверенный документ в колонку `XML`. `XmlService.validate(xml, schemaLocation)` проверяет документ по XSD.
Для контракта без XSD `validate(xml)` проверяет синтаксис всего документа. Обычный текст, JSON и необработанные
сообщения в маршруте ошибок хранятся в `TEXT`. Лимит входного тела измеряется в байтах UTF-8.

Liquibase создаёт таблицы этой версии на чистой схеме. Проект находится на стадии прототипа: для новой версии
используйте пустую схему. Совместимость с данными и миграциями прежних прототипов не поддерживается.

## Границы текущей реализации

Сервис поддерживает JMS-вход, Java-обработчики и выходы JMS/Kafka. Асинхронное обогащение рассчитано на один JMS-вызов
на документ. Терминальные ошибки доступны через таблицы доставки и метрики. Ручное восстановление выполняет оператор.
Перед эксплуатацией проверьте обработчики, настройки и контракт внешнего сервиса на целевом JMS-провайдере.
