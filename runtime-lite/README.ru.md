# Runtime Lite

`runtime-lite` — отдельное JMS-приложение на Java 21, Spring Boot 4.1.2 и PostgreSQL. Оно читает JMS-сообщения, вызывает
Java-обработчик и надёжно записывает исходящие сообщения в outbox. Это независимый Maven-проект: он не использует Camel,
не является модулем конвертера и не получает его результат автоматически.

## Структура и обработчик

- `inbox` принимает JMS-сообщения и обрабатывает их в транзакции БД.
- `processor` содержит функциональный интерфейс `MessageProcessor`.
- `outbox` хранит исходящие сообщения и отправляет их в JMS или Kafka.
- `transport` принимает и отправляет JMS `TextMessage`, ограничивает размер тела и отправляет текст в Kafka.
- `config` содержит общие конфигурации, включая `config.transport`, `config.inbox` и `config.xml`.
- `jdbc` и `xml` содержат поддержку работы с таблицами доставки и защищённую обработку XML.

Зарегистрируйте обработчик как Spring bean. Имя bean укажите в `runtime-lite.routes.<id>.processor`:

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

В обработчик можно внедрять обычные Spring-сервисы, репозитории Spring Data JDBC и `XmlMapper` Jackson 3. Для
преобразования моделей доступны MapStruct и Lombok; их совместная обработка аннотаций настроена в Maven. Компоненты
размещайте внутри пакета `ru.petrovov.iib_camel.runtime_lite` или явно добавьте свои пакеты в сканирование Spring.

## Работа с XML и XSD

Spring предоставляет `XmlService` для определения корня документа, преобразования XML в DTO и обратно. Проверка по
XSD выбирается явно для каждого вызова. Схемы располагаются в ресурсах приложения и задаются путём `classpath:`;
скомпилированные схемы повторно используются. XML передаётся как `String`. Например:

```java
QName root = xmlService.detectRoot(message.body());

SampleRequest request = xmlService.readValidated(
    message.body(),
    "classpath:schemas/sample/request.xsd",
    SampleRequest.class
);

SampleResponse response = handle(request);
String output = xmlService.writeValidated(response, "classpath:schemas/sample/response.xsd");
return OutboundMessage.of(Transport.JMS, "SAMPLE.OUT", output);
```

Пример показывает вызовы внутри `MessageProcessor`: `XmlService` внедряется как Spring-зависимость, а DTO, схемы и
метод `handle` определяются конкретной интеграцией. Для формата без XSD используются `read(text, Dto.class)` и
`write(dto)`. Для настройки сериализации можно получить `ObjectWriter` вызовом `writerFor(Dto.class)` и передать его
в `write(dto, writer)` или `writeValidated(dto, writer, schemaLocation)`.

`detectRoot` возвращает `QName`: сравнивайте локальное имя и URI namespace, а не строковый префикс. При чтении без XSD
сервис проверяет корректность XML, но не подтверждает соответствие внешнему контракту. Для обязательной схемы ошибка её
загрузки или проверки не приводит к пропуску валидации. Значения по умолчанию, заданные XSD, при раздельной проверке
и десериализации исходного текста автоматически в DTO не добавляются.

Сервис запрещает DTD и внешние XML-сущности. XSD подключается только из classpath, а разрешённые локальные зависимости
схемы должны находиться рядом с корневой схемой или в её подкаталогах. Сетевые и произвольные файловые ссылки не
загружаются. Jackson не проверяет все особенности XML-схем и не гарантирует проверку namespace при десериализации;
для форматов без XSD нужные ограничения и namespace следует проверять явно. При ошибке процессора транзакция
откатывается, дальнейшая повторная доставка зависит от настроек брокера.

## Синтетический пример с профилем local

Пример обработчика находится в `src/main/java/ru/petrovov/iib_camel/runtime_lite/processor/example`, его XSD —
`src/main/resources/schemas/example/payment.xsd`, а готовое сообщение — в `examples/payment.xml`.
Профиль `local` подключает обработчик к очереди Artemis `ENRICH.TEST.IN`. Обработчик проверяет XML по XSD и
публикует исходный текст в Kafka topic `example_topic`. Если `sendNotification` равно `true`, он также публикует
JSON-уведомление в `example_mail_topic`. Имена topic примера задаются настройками `runtime-lite.example.*` в
`src/main/resources/application-local.yaml`. Схема принимает для `sendNotification` значения `true` и `false`.

Невалидный XML направляется в `example_error_topic` с заголовком `example_error_code=INVALID_XML`, а документ
с `amount <= 0` — с заголовком `example_error_code=NON_POSITIVE_AMOUNT`. В обоих случаях тело сообщения об ошибке
содержит исходный текст, а исключение не включается в код ошибки. Исключения `XmlService` при чтении или проверке
документа в этом примере относятся к `INVALID_XML`. Загрузка XSD выполняется отдельно: её ошибки, ошибки БД и outbox,
а также другие исключения вне чтения XML откатывают транзакцию; повторная доставка входного сообщения зависит от
настроек брокера.

DTO задают порядок элементов для сериализации, соответствующий XSD. Тест проверяет путь XML → DTO → XML с повторной
проверкой по схеме. Исходный платёж и сообщение об ошибке могут содержать данные документа, поэтому доступ к topic
необходимо настроить соответствующим образом.

Для ручной проверки подготовьте PostgreSQL, Artemis и Kafka и необходимые очередь и топики на стороне брокеров:
приложение не создаёт брокеры. Укажите подключения и учётные данные через переменные окружения Spring, например
`LITE_DB_URL`, `LITE_DB_USER`, `LITE_DB_PASSWORD`, `LITE_ARTEMIS_URL`, `LITE_ARTEMIS_USER`,
`LITE_ARTEMIS_PASSWORD` и `LITE_KAFKA_BOOTSTRAP_SERVERS`. Соберите проект на JDK 21 и запустите JAR:

```sh
mvn -B -f runtime-lite/pom.xml package
java -jar runtime-lite/target/iib-camel-runtime-lite-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Отправьте содержимое `runtime-lite/examples/payment.xml` как JMS `TextMessage` в очередь `ENRICH.TEST.IN` с помощью
доступного JMS-клиента или консоли Artemis. Проверьте сообщения в основном и mail topic через Kafka consumer.
Для проверки ошибок отдельно отправьте некорректный XML и документ с нулевой суммой, затем проверьте error topic
и заголовок. Очередь и топики должны быть доступны приложению и клиентам; политика их создания задаётся брокером.

Для интеграционной проверки примера используются настройки `src/test/resources/application-example-test.yaml`.
Тесты полного профиля запускаются командой `mvn -B -f runtime-lite/pom.xml -Pint-test verify` после подготовки
тестовых сервисов, описанной ниже. Дополнительно нужна выделенная пустая очередь `LITE.EXAMPLE.TEST.IN`; её имя
можно изменить переменной `IIB_LITE_EXAMPLE_TEST_INPUT_QUEUE`.

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
`spring.kafka.security`; настройки только производителя — в `spring.kafka.producer`. Общие параметры доступны также
тестовым Kafka consumer и AdminClient. XML mapper отключает обработку DTD и внешних сущностей. Входное тело
ограничено `max-body-bytes`.

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

Обычный `verify` запускает доступные тесты. Тесты PostgreSQL включаются, когда заданы переменные
`IIB_LITE_TEST_POSTGRES_URL`, `IIB_LITE_TEST_POSTGRES_USER` и `IIB_LITE_TEST_POSTGRES_PASSWORD`; без URL они
пропускаются. Общие тестовые настройки находятся в `src/test/resources/application-test.yaml`, настройки тестов БД —
в `application-db-test.yaml`.

Для полного сквозного теста нужны PostgreSQL, Artemis и Kafka. Настройки подключения и имена двух отдельных очередей
задаются в `runtime-lite/src/test/resources/application-int-test.yaml`: `spring.datasource.*`,
`runtime-lite.jms.provider`, `runtime-lite.artemis.*`, `spring.kafka.bootstrap-servers`,
`runtime-lite.routes.broker.input-queue`. Выходная очередь задаётся параметром тестового обработчика
`IIB_LITE_INT_OUTPUT_QUEUE` (по умолчанию `LITE.TEST.OUT`). Перед запуском укажите доступные
тестовые сервисы и две выделенные пустые JMS-очереди; для Artemis это очереди ANYCAST. Настройки можно передать через
`IIB_LITE_INT_POSTGRES_URL`, `IIB_LITE_INT_POSTGRES_USER`, `IIB_LITE_INT_POSTGRES_PASSWORD`,
`IIB_LITE_INT_ARTEMIS_URL`, `IIB_LITE_INT_ARTEMIS_USER`, `IIB_LITE_INT_ARTEMIS_PASSWORD`, `IIB_LITE_INT_KAFKA`,
`IIB_LITE_INT_INPUT_QUEUE` и `IIB_LITE_INT_OUTPUT_QUEUE`. Настоящие пароли в репозитории
не храните: передавайте их через переменные окружения или другие локальные настройки Spring.

Используйте выделенные тестовые брокеры и базу: тест потребляет и подтверждает сообщения из очередей, но не очищает
их. Перед запуском listener он проверяет, что очереди разные и пустые. Он создаёт случайные изолированные схему
PostgreSQL и Kafka topic и удаляет их после выполнения; учётным данным
нужны права на создание и удаление. При аварийном завершении может потребоваться удалить оставшиеся ресурсы вручную.
Тест проверяет откат первой обработки, повторную доставку и автоматическую отправку из outbox в Artemis и Kafka.
Он не проверяет специфичные возможности IBM MQ.

Запуск из корня репозитория в shell:

```sh
mvn -B -f runtime-lite/pom.xml -Pint-test verify
```

В Windows PowerShell:

```powershell
mvn -B -f runtime-lite/pom.xml -Pint-test verify
```

`-B` включает пакетный режим Maven, `-f` выбирает POM независимого проекта, `-Pint-test` включает Maven-профиль
сквозного теста. Maven-профиль управляет запуском теста и активирует соответствующий Spring-профиль `int-test`;
Spring загружает его настройки из `application-int-test.yaml` вместе с общими тестовыми настройками.

После добавления обработчика и настроек запустите собранное приложение:

```sh
java -jar runtime-lite/target/iib-camel-runtime-lite-0.0.1-SNAPSHOT.jar
```

`spring.liquibase.default-schema` задаёт общую схему PostgreSQL для приложения; по умолчанию это `runtime_lite`.
Её можно указать через `LITE_DB_SCHEMA` или стандартную настройку Spring. Пул Hikari устанавливает эту же схему
для каждого соединения, поэтому обычные запросы без имени схемы и Spring Data JDBC используют её по умолчанию.
Служебные запросы inbox/outbox дополнительно указывают схему явно. Liquibase создаёт в ней свои таблицы истории и
блокировки, а также `inbound_receipt` и `outbox_message`. Явно указанная в SQL или отображении сущности другая схема
сохраняет своё значение.

Сервис создаёт схему при запуске, если её ещё нет; пользователю БД нужны необходимые права. Имя должно начинаться со
строчной латинской буквы или `_` и содержать не более 63 строчных латинских букв, цифр и `_`. Не переопределяйте
`spring.datasource.hikari.schema` или `spring.liquibase.liquibase-schema` отдельно: при несовпадении приложение не
запустится. Смена схемы не переносит данные из прежней схемы.

Входной JMS message ID вместе с
ID маршрута образуют ключ идемпотентности. Inbox receipt, бизнес-изменения обработчика и все исходящие записи фиксируются
одной транзакцией PostgreSQL. JMS listener использует транзакционную JMS session, но XA между JMS и БД не настроен:
подтверждение JMS и транзакция БД не образуют общей атомарной транзакции.
При ошибке обработки JMS-сессия откатывается; повторная доставка и обработка неуспешного сообщения определяются
настройками брокера. Переименование маршрута меняет ключ дедупликации, поэтому учитывайте уже полученные сообщения.

Планировщик забирает PENDING записи с блокировкой `SKIP LOCKED`, отправляет их с подтверждением транспорта и затем
отмечает SENT. Сбой после отправки, но до отметки SENT может вызвать повторную отправку; отправка имеет семантику
at-least-once, а после `max-attempts` запись требует ручного восстановления. Потребителям следует дедуплицировать по
передаваемому заголовку Kafka или свойству JMS `integration_message_id`. При ошибке запись повторяется после `retry-delay`, пока число попыток не достигнет
`max-attempts`; затем статус становится FAILED и запись больше не выбирается. Автоматическая очистка записей не
реализована. Оператор может проверить `last_error` и число попыток, устранить причину и вручную вернуть запись в
PENDING, сохранив её содержимое, например:

```sql
UPDATE runtime_lite.outbox_message
SET status = 'PENDING', available_at = CURRENT_TIMESTAMP, last_error = NULL
WHERE id = '00000000-0000-4000-8000-000000000001' AND status = 'FAILED';
```

Укажите фактическую схему и UUID записи при восстановлении. Изменение статуса не обнуляет счётчик attempts: после
следующей ошибки запись снова перейдёт в FAILED. Для полного нового цикла повторов можно также установить `attempts = 0`.
Перед повторным запуском убедитесь, что причина ошибки устранена.

Порядок доставки между сообщениями не гарантируется: несколько экземпляров могут отправлять записи параллельно.
Записи содержат тело и заголовки сообщений; настройте доступ к БД и срок их хранения с учётом данных приложения.

## Логирование

Логи выводятся только в консоль: при активном Spring-профиле `local` или `test` используется обычный текстовый формат,
иначе — JSON (Logstash). На уровне INFO записываются тело полученного сообщения и информация о сохранении сообщения
в outbox после фиксации транзакции. Технические подробности обработки и отправки доступны на уровне DEBUG;
ошибки доставки отражаются на уровне WARN, а подробности исключений — на DEBUG. Тела сообщений могут содержать
конфиденциальные данные: учитывайте это при настройке доступа к журналам и срока их хранения.

## Текст сообщений

Публичные тела сообщений и XML API теперь используют `String`; JMS принимает и отправляет только `TextMessage`,
Kafka producer использует `StringSerializer`, а тело outbox хранится в PostgreSQL как `TEXT`. Лимит входного тела
по-прежнему измеряется в байтах UTF-8. Заголовки Kafka кодируются в UTF-8 согласно API Kafka. PostgreSQL `TEXT`
не поддерживает нулевой символ.

Liquibase создаёт таблицы для чистой базы данных. Отправители JMS должны посылать `TextMessage`, получатели JMS —
принимать его, а потребители Kafka должны декодировать текст в UTF-8.

## Границы текущей реализации

Реализация ограничена JMS-входом, простым Java `MessageProcessor` и выходами JMS/Kafka. Нет готовых бизнес-обработчиков,
административного API для FAILED, очистки/архивации outbox, метрик и полноценной операционной панели. Штатный выбор
JMS-провайдера поддерживает IBM MQ и Artemis; специфичные возможности и настройки IBM MQ не переносятся автоматически
на Artemis. Поведение необходимо проверить на целевых брокерах и схеме БД до
эксплуатации.
