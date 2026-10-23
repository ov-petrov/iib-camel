# Исключения для комментариев в коде

В исходном и генерируемом коде комментарии и Javadoc по умолчанию отсутствуют. Назначение классов, инструкции запуска,
трассируемость конвертации и причины ручной миграции описываются в документации и отчётах.

Сохранены только шесть коротких комментариев:

1. [InboxService.accept](../runtime/runtime-delivery/src/main/java/ru/petrovov/iib_camel/runtime/delivery/inbox/InboxService.java) —
   вход можно подтвердить после возврата метода; бизнес-записи участвуют в транзакции только с тем же `DataSource`.
2. [OutboxDispatcher.dispatchOnce](../runtime/runtime-delivery/src/main/java/ru/petrovov/iib_camel/runtime/delivery/outbox/OutboxDispatcher.java) —
   отказ БД после подтверждения брокера оставляет захват для повторной отправки с проверкой токена; его нельзя
   обработать как обычный отказ брокера.
3. [ExampleModuleConfiguration.exampleReceive](../runtime/modules/example-module/src/main/java/ru/petrovov/iib_camel/runtime/modules/example/ExampleModuleConfiguration.java) —
   Kafka offset подтверждается на потоке потребителя после коммита БД.
4. [KafkaTransportConfiguration](../runtime/runtime-core/src/main/java/ru/petrovov/iib_camel/runtime/transport/config/KafkaTransportConfiguration.java) —
   начальная позиция новой consumer group должна сохранять возможность повторить первое сообщение, offset которого ещё
   не зафиксирован.
5. [TlsContextFactory](../runtime/runtime-core/src/main/java/ru/petrovov/iib_camel/runtime/transport/config/TlsContextFactory.java) —
   `SSLContext.init(null, ...)` не наследует JVM key managers; для клиентского сертификата требуется явное получение
   менеджеров ключей.
6. [JmsDeliveryPolicy](../compatibility-tests/legacy-runtime/src/main/java/ru/petrovov/iib_camel/runtime/config/JmsDeliveryPolicy.java) —
   повторять нужно всю транзакцию брокера, а не только упавший процессор.

Маркеры комментариев внутри строк, регулярных выражений и синтетических входов парсерных тестов являются данными и не
удаляются. Они проверяют, что конвертер правильно обрабатывает комментарии исходного языка; это не поясняющие
комментарии Java.
