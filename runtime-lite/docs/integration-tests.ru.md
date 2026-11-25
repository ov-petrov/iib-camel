# Интеграционные тесты runtime-lite с PostgreSQL и Artemis

Профиль Maven `int-test` запускает сквозные сценарии на PostgreSQL и Artemis, а также проверки транзакций, повторной
отправки и таймаутов на PostgreSQL. Для всего прогона достаточно этих двух сервисов.

| Класс | Что проверяет |
| --- | --- |
| `BrokerPipelineIntegrationTest` | Обычный `MessageProcessor`. Откат первой обработки, повторную доставку Artemis и получение двух сообщений из выходной очереди после успешной попытки. |
| `EnrichmentBrokerIntegrationTest` | `EnrichingMessageProcessor`. Два XML-запроса, ответы в обратном порядке и один дубль. Тест читает из Artemis два итоговых XML и проверяет результат обогащения. |
| `PostgresDeliveryTest` | Миграции, дедупликацию, транзакции и повторные попытки outbox. |
| `PostgresEnrichmentWorkflowTest` | Пропуск вызова, маршрутизацию исходного сообщения при таймауте и ошибках ответа, повтор после отката и конкурентное возобновление на PostgreSQL. |

## Подключения и изоляция

Подготовь отдельную тестовую БД или подключись к тестовой БД на существующем PostgreSQL. Роли теста нужны
`CONNECT` и `CREATE` на этой БД. Схему создаст сам сервис при запуске теста, а Liquibase создаст в ней таблицы.
В Artemis нужны права `send` и `consume` для тестового префикса `LITE.E2E.#`. Если очереди создаёт брокер, включи
`auto-create-addresses` и `auto-create-queues` для этого префикса и разреши роли `createAddress` и
`createDurableQueue`.
Для ручного создания используй одноимённые адреса и очереди с маршрутизацией ANYCAST.
[Права Artemis](https://artemis.apache.org/components/artemis/documentation/latest/security.html#role-based-security-for-addresses) · [Автосоздание и ANYCAST](https://artemis.apache.org/components/artemis/documentation/latest/address-model.html)

| Переменная окружения | Свойство в Spring | Пример |
| --- | --- | --- |
| `IIB_LITE_INT_POSTGRES_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/runtime_lite_test` |
| `IIB_LITE_INT_POSTGRES_USER` | `spring.datasource.username` | `runtime_lite_test` |
| `IIB_LITE_INT_POSTGRES_PASSWORD` | `spring.datasource.password` | Пароль тестовой роли |
| `IIB_LITE_INT_ARTEMIS_URL` | `runtime-lite.artemis.broker-url` | `tcp://localhost:61616` |
| `IIB_LITE_INT_ARTEMIS_USER` | `runtime-lite.artemis.username` | `lite_test` |
| `IIB_LITE_INT_ARTEMIS_PASSWORD` | `runtime-lite.artemis.password` | Пароль тестового пользователя Artemis |

Оба вида интеграционных тестов читают один файл настроек. В нём `runtime-lite.jms.provider` уже задан как `ARTEMIS`.
В URL Artemis укажи порт подключения JMS-клиента. [application-int-test.yaml](../src/test/resources/application-int-test.yaml)

Каждый сквозной класс генерирует свой случайный суффикс при запуске JVM. Схема и очереди теста используют один суффикс.

| Тест | Схема PostgreSQL | Очереди Artemis |
| --- | --- | --- |
| `BrokerPipelineIntegrationTest` | `lite_broker_<suffix>` | `LITE.E2E.DIRECT.IN.<suffix>`, `LITE.E2E.DIRECT.OUT.<suffix>` |
| `EnrichmentBrokerIntegrationTest` | `lite_e2e_<suffix>` | `LITE.E2E.<роль>.<suffix>` с ролями `A`, `B`, `REQUEST`, `REPLY`, `OUTPUT` |

Тесты PostgreSQL также создают отдельные схемы. Имена задаются через `@DynamicPropertySource` и тестовые константы.
Если автосоздание очередей запрещено, поставь точку останова в методе с `@DynamicPropertySource` нужного сквозного
класса. Скопируй имена очередей из его полей, создай соответствующие пары адресов и очередей ANYCAST и продолжи запуск.

## Запуск из Windows PowerShell

Выполни из корня репозитория на JDK 21. Подставь свои адреса и имена пользователей. Пароли вводятся в текущей сессии
PowerShell и передаются Maven через окружение.

```powershell
$env:IIB_LITE_INT_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/runtime_lite_test'
$env:IIB_LITE_INT_POSTGRES_USER = 'runtime_lite_test'
$env:IIB_LITE_INT_POSTGRES_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
$env:IIB_LITE_INT_ARTEMIS_URL = 'tcp://localhost:61616'
$env:IIB_LITE_INT_ARTEMIS_USER = 'lite_test'
$env:IIB_LITE_INT_ARTEMIS_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Artemis password' -AsSecureString)).Password

mvn -B -f runtime-lite/pom.xml -Pint-test verify
```

Чтобы выполнить один сценарий, укажи имя класса через `-Dtest`. Для проверки только JMS-обогащения выполни команду.

```powershell
mvn -B -f runtime-lite/pom.xml -Pint-test '-Dtest=EnrichmentBrokerIntegrationTest' verify
```

Для этого запуска ожидается итог Surefire `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Текстовый отчёт
сохраняется в `runtime-lite/target/surefire-reports`. В логе приложения ожидаются две строки
`JMS-обогащение завершено` с результатом `COMPLETED`.

## Как посмотреть обмен при обогащении

1. Запусти `EnrichmentBrokerIntegrationTest` через **Debug** в IDE с теми же шестью переменными и VM option
   `-Druntime-lite.integration-test=true`. Spring-профиль `int-test` уже указан в классе. Для подключения
   отладчика к запуску Maven добавь `'-Dmaven.surefire.debug'` перед `verify` в команду для одного класса и
   подключись к порту `5005`.
   [Отладка тестов Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/examples/debugging.html)
2. Чтобы увидеть **исходящие запросы**, остановись на первом `reply(context, second)`. В `first` и `second` можно
   посмотреть `getText()`, `getJMSReplyTo()` и `getJMSCorrelationID()`. В PostgreSQL обработки перейдут в
   `WAITING_REPLY` после фиксации отправки. Для этой точки используй **Suspend Thread** и продолжи тест до истечения
   45 секунд с отправки запроса. PostgreSQL продолжает отсчёт во время паузы.
3. Для **ответа и результата** остановись на `reply.setJMSCorrelationID(...)` или после чтения `resultA` и `resultB`.
   Если нужно спокойно посмотреть БД и счётчики после всех проверок, поставь точку в начале `cleanup()`, до удаления
   схемы. Тест отправляет ответы сам.
4. В Artemis Web Console открой **Queues** и найди очереди по общему суффиксу. В **Messages Added** ожидаются
   `A = 1`, `B = 1`, `REQUEST = 2`, `REPLY = 3`, `OUTPUT = 2`. Третий ответ — намеренный дубль.
   `Message Count` может уже быть нулевым, потому что тестовые потребители подтвердили сообщения. Для просмотра
   используй **Browse** или счётчики очереди, чтобы отдельный потребитель не забрал сообщение у теста.
   [Artemis Web Console](https://artemis.apache.org/components/artemis-console/documentation/latest/)

### Проверка в PostgreSQL

Возьми имя схемы из `SCHEMA` в отладчике или найди его запросом.

```sql
SELECT nspname
FROM pg_namespace
WHERE left(nspname, 9) = 'lite_e2e_';
```

Замени `lite_e2e_SUFFIX` во всех строках запроса на имя схемы текущего прогона.

```sql
SELECT i.source_destination, i.processor_id,
       w.correlation_id, w.status, w.failure_code,
       w.ready_at, w.reply_received_at, w.reply_message_id,
       w.result_outbox_id,
       req.status AS request_delivery,
       res.destination AS result_destination,
       res.status AS result_delivery,
       res.body AS result_body,
       res.body_xml::text AS result_xml
FROM lite_e2e_SUFFIX.enrichment_workflow w
JOIN lite_e2e_SUFFIX.inbound_message i USING (route_id, message_id)
JOIN lite_e2e_SUFFIX.outbox_message req ON req.id = w.request_outbox_id
LEFT JOIN lite_e2e_SUFFIX.outbox_message res ON res.id = w.result_outbox_id
ORDER BY i.processor_id;
```

После успешного прогона ожидаются две обработки `COMPLETED` и два итоговых XML с `reply-alpha` и `reply-beta`.
`COMPLETED` означает, что результат записан в outbox. `SENT` в `result_delivery` показывает подтверждённую отправку
брокеру. Сам тест проверяет доставку тем, что получает оба сообщения из очереди `OUTPUT`. Тело ответа после завершения
очищается, а `reply_received_at` и `reply_message_id` остаются.

При отладке сценариев со сбоем `ready_at` показывает момент перехода в `READY_TO_MAP`. После обработки сбоя в
одной строке workflow видны `FAILED`, причина в `failure_code` и ссылка на выходное сообщение в `result_outbox_id`.
Для маршрута ошибок в тестах PostgreSQL проверь `result_destination` и исходное тело в `result_body`.

При завершении сквозного теста его схема удаляется через `DROP SCHEMA ... CASCADE`. Очереди удаляются по политике
брокера. Если они остаются, их можно убрать в тестовом Artemis по суффиксу конкретного запуска.

## Как посмотреть обычную обработку

Запусти `BrokerPipelineIntegrationTest` и поставь точку останова в начале `cleanup()`. В схеме
`lite_broker_<suffix>` должна остаться одна строка в `inbound_message` и две строки `SENT` в `outbox_message`.
Процессор в успешной попытке отправляет одно сообщение через `MessagePublisher` и ещё одно возвращает как результат.
Строк в `enrichment_workflow` нет. В счётчике **Messages Added** ожидается одно входное сообщение и два выходных.
Повторная доставка использует то же входное сообщение.

## Проверки только на PostgreSQL

Те же настройки PostgreSQL подходят для проверок с тестовой отправкой вместо JMS. Чтобы выполнить только их, выбери
два класса в том же профиле.

```powershell
mvn -B -f runtime-lite/pom.xml -Pint-test '-Dtest=PostgresDeliveryTest,PostgresEnrichmentWorkflowTest' verify
```
