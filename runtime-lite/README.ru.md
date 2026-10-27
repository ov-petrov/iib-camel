# Runtime Lite

`runtime-lite` — отдельное JMS-приложение на Java 21, Spring Boot 4.1.2 и PostgreSQL. Оно читает JMS-сообщения, вызывает
Java-обработчик и надёжно записывает исходящие сообщения в outbox. Это независимый Maven-проект: он не использует Camel,
не является модулем конвертера и не получает его результат автоматически.

## Структура и обработчик

- `inbox` принимает JMS-сообщения и обрабатывает их в транзакции БД.
- `processor` содержит функциональный интерфейс `MessageProcessor`.
- `outbox` хранит исходящие сообщения и отправляет их в JMS или Kafka.
- `transport` читает JMS-форматы `TextMessage`/`BytesMessage`, ограничивает размер тела и отправляет сообщения.
- `config`, `jdbc`, `xml` задают YAML-настройки, имена таблиц, Liquibase и защищённый XML mapper.

Зарегистрируйте обработчик как Spring bean. Имя bean укажите в `runtime-lite.routes.<id>.processor`:

```java
@Component("sampleProcessor")
public class SampleProcessor implements MessageProcessor {
    @Override
    public MessagePayload process(InboundMessage message) {
        return MessagePayload.of(message.body());
    }
}
```

Обработчик возвращает итоговое тело сообщения. Для дополнительной отправки внедрите `MessagePublisher` и вызовите
`send("audit", payload)` внутри `process`. И возвращаемое сообщение маршрута, и дополнительные сообщения publisher
попадают в одну транзакционную outbox; `MessagePublisher` не отправляет их синхронно в брокер. Имена destination — ключи из
`runtime-lite.destinations`, а транспорт и физическое назначение задаются там.

В обработчик можно внедрять обычные Spring-сервисы, репозитории Spring Data JDBC и `XmlMapper` Jackson 3. Для
преобразования моделей доступны MapStruct и Lombok; их совместная обработка аннотаций настроена в Maven. Компоненты
размещайте внутри пакета `ru.petrovov.iib_camel.runtime_lite` или явно добавьте свои пакеты в сканирование Spring.

## Конфигурация

Минимальный пример использует только синтетические идентификаторы:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/runtime_lite
    username: runtime_lite
    password: ${LITE_DB_PASSWORD}

runtime-lite:
  mq:
    host: localhost
    port: 1414
    queue-manager: QM1
    channel: DEV.APP.SVRCONN
    username: ${LITE_MQ_USER:}
    password: ${LITE_MQ_PASSWORD:}
  kafka:
    bootstrap-servers: [localhost:9092]
    send-timeout: 30s
  listeners:
    auto-startup: true
    max-body-bytes: 10485760
  outbox:
    schema: runtime_lite
    batch-size: 20
    max-attempts: 10
    retry-delay: 10s
    poll-interval: 1s
  routes:
    sample:
      input-queue: SAMPLE.IN
      processor: sampleProcessor
      output: sampleOutput
      concurrency: 1
  destinations:
    sampleOutput:
      transport: JMS
      name: SAMPLE.OUT
    audit:
      transport: KAFKA
      name: sample.audit
```

Установите `LITE_DB_PASSWORD`, `LITE_MQ_USER` и `LITE_MQ_PASSWORD` через секрет-хранилище окружения. Не помещайте
секреты в YAML или репозиторий. IBM MQ принимает пару username/password; TLS настраивается `cipher-suite` и при
необходимости `peer-name`. Для Kafka можно передать настройки производителя через `runtime-lite.kafka.properties`, в
том числе параметры безопасности Kafka клиента. XML mapper отключает обработку DTD и внешних сущностей. Входное тело ограничено
`max-body-bytes`.

## Сборка и доставка

Требуется JDK 21 и Maven 3.9+:

```sh
mvn -B -f runtime-lite/pom.xml verify
```

Тесты PostgreSQL выполняются при заданных `IIB_LITE_TEST_POSTGRES_URL`, `IIB_LITE_TEST_POSTGRES_USER` и
`IIB_LITE_TEST_POSTGRES_PASSWORD`. Без URL они пропускаются. Для сквозного теста дополнительно установите
`IIB_LITE_TEST_BROKERS=true` и `IIB_LITE_TEST_MQ_INPUT_QUEUE` / `IIB_LITE_TEST_MQ_OUTPUT_QUEUE`.
Используйте отдельные временные брокеры и выделенные пустые очереди: тест потребляет и подтверждает сообщения,
создаёт и удаляет собственный временный Kafka topic. Адреса и учётные данные задаются через `IIB_LITE_TEST_MQ_*`
и `IIB_LITE_TEST_KAFKA`.

После добавления обработчика и настроек запустите собранное приложение:

```sh
java -jar runtime-lite/target/iib-camel-runtime-lite-0.0.1-SNAPSHOT.jar
```

Таблицы `inbound_receipt` и `outbox_message` создаются Liquibase в настроенной схеме. Используйте отдельную схему для
приложения: смена `runtime-lite.outbox.schema` не переносит данные из прежней схемы. Входной JMS message ID вместе с
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

## Границы текущей реализации

Реализация ограничена JMS-входом, простым Java `MessageProcessor` и выходами JMS/Kafka. Нет готовых бизнес-обработчиков,
административного API для FAILED, очистки/архивации outbox, метрик и полноценной операционной панели. Для замены IBM MQ
нужно отключить его фабрику через `runtime-lite.mq.enabled: false` и предоставить собственный Spring `ConnectionFactory`.
Специализированная конфигурация, эксплуатационные
настройки и полная поддержка Artemis не реализованы. Поведение необходимо проверить на целевых брокерах и схеме БД до
эксплуатации.
