# Шаблон модуля runtime

Модуль интеграции — отдельная Maven-библиотека, подключаемая приложением runtime при сборке. Используйте нейтральный идентификатор, соответствующий шаблону `[a-z][a-z0-9-]*`, например `example-module`.

## Структура

```text
modules/example-module/
├── pom.xml
├── README.ru.md
└── src/main/
    ├── java/ru/petrovov/iib_camel/runtime/modules/example_module/
    │   └── ExampleModuleConfiguration.java
    └── resources/
        └── modules/example-module/routes/
            └── example-route.xml
```

В Java package используйте имя по правилам Java: дефис идентификатора замените на подчёркивание. Ресурсный путь сохраняет исходный идентификатор. Все маршруты одного модуля размещаются в его каталоге `modules/<id>/routes/`, чтобы Camel не подхватил маршруты соседних модулей.

## Maven-регистрация

Создайте `modules/example-module/pom.xml` как библиотеку с groupId `ru.petrovov.iib_camel`, артефактом `iib-camel-example-module` и зависимостью от runtime core с версией из родительского проекта. Добавьте каталог `modules/example-module` в список модулей `runtime/pom.xml`, а затем добавьте зависимость `iib-camel-example-module` в `runtime/runtime-app/pom.xml`. Используйте ту же версию, что и остальные runtime-модули.

После изменения Maven-конфигурации пересоберите приложение:

```bash
cd runtime
mvn -B -pl runtime-app -am package
```

Приложение и модули образуют один артефакт поставки. Добавление JAR в запущенный процесс не регистрирует модуль автоматически.

## Контракт модуля

Добавьте Spring-конфигурацию модуля с зарегистрированным `IntegrationModule`. Его `id` должен совпадать с именем каталога, а `routesPattern` должен ограничивать загрузку путём `classpath*:modules/<id>/routes/`. Для модуля `example-module` шаблон маршрутов — `classpath*:modules/example-module/routes/*.xml`.

Модуль не должен включать маршруты других модулей или включать транспорт, отключённый внешней конфигурацией runtime. Текущие имена Camel-компонентов задаются runtime-конфигурациями: `artemis`, `ibmmq` и `kafka`. Например, отдельные входные маршруты могут начинаться с `artemis:queue:INPUT.ARTEMIS`, `ibmmq:queue:INPUT.MQ` и `kafka:INPUT.KAFKA?groupId=example-runtime`; все три transport-флага можно включить одновременно. Kafka использует компонент `kafka` с ручным подтверждением offset после фиксации inbox-транзакции. Не используйте предполагаемые схемы URI вроде `runtimeKafka:`.

Используйте `id="<module-id>.<route-name>"` для каждого маршрута: загрузчик отклоняет повторяющиеся и чужие идентификаторы. Добавьте `errorHandlerRef="runtimePropagateErrors"` на входной маршрут, чтобы ошибка БД вернулась потребителю. Не добавляйте `handled(true)` перед фиксацией inbox: это может подтвердить необработанное сообщение.

Автоконфигурацию модуля объявите через `@AutoConfiguration`, ограничьте `@ConditionalOnProperty` и внесите полное имя класса в `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Обычный `@Component` в произвольном каталоге автоматически не обнаруживается: приложение сканирует только собственный пакет. В готовом модуле `example` есть полный пример регистрации.

Бизнес-обработчик передаётся в `InboxService.accept`. Его `JdbcTemplate` должен использовать тот же `DataSource`: тогда изменения бизнес-таблиц присоединятся к транзакции inbox/outbox. Прямые отправки брокеру внутри этого обработчика нарушают такую атомарность; вместо них верните список `OutboundMessage` с выбранными транспортами и назначениями. Это также подходит для чтения бизнес-записей планировщиком и переноса их в outbox: изменение статуса исходной записи выполняйте внутри обработчика. JPA по умолчанию не включён; его добавление требует согласовать менеджер транзакций, а не только добавить зависимость Hibernate.

У встроенного примерного модуля одно свойство `IIB_MODULE_EXAMPLE_INPUT_URI`, поэтому оно задаёт один вход. Для нескольких источников добавьте отдельные маршруты с соответствующими URI, включите нужные `IIB_TRANSPORTS_*_ENABLED` флаги и `IIB_MODULE_EXAMPLE_ENABLED=true`. Например:

```properties
IIB_TRANSPORTS_ARTEMIS_ENABLED=true
IIB_TRANSPORTS_IBMMQ_ENABLED=true
IIB_TRANSPORTS_KAFKA_ENABLED=true
IIB_MODULE_EXAMPLE_ENABLED=true
IIB_MODULE_EXAMPLE_INPUT_URI=artemis:queue:INPUT.ARTEMIS
IIB_MODULE_EXAMPLE_OUTPUT_TRANSPORT=kafka
IIB_MODULE_EXAMPLE_OUTPUT_DESTINATION=EXAMPLE.OUT
IIB_TRANSPORTS_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092
```

Укажите требуемые флаги `iib.transports.*.enabled` и настройки соединения в документации. Не помещайте реальные логины и пароли в POM, XML, Java или примеры.

## Обязательная документация

Заполните `README.ru.md` до подключения модуля к приложению. Опишите:

- назначение модуля и его нейтральный идентификатор;
- транспорт, входящие и исходящие назначения;
- необходимые свойства, значения по умолчанию и безопасное получение секретов;
- формат сообщения, стабильный идентификатор события и правила дедупликации;
- поведение при повторной доставке, временной ошибке и постоянном отказе;
- локальный запуск на синтетических данных и требования к брокеру;
- ограничения, метрики и порядок включения в конкретной поставке.

Добавляйте только самостоятельно созданные синтетические данные и нейтральные названия. Фотографии источников, экспортированные артефакты и реальные бизнес-примеры остаются вне Git либо в игнорируемом `.private/`.
