# Подключения и безопасность

Все три транспорта включаются независимо: `iib.transports.artemis.enabled`, `iib.transports.ibmmq.enabled`, `iib.transports.kafka.enabled`. Их значения по умолчанию — `false`. Базовые адреса и авторизация заполнены для локального стенда в `deploy/.env.example`; реальные значения задаются только внешней конфигурацией.

Для защищённой среды можно подключить внешний `application.properties` через `spring.config.additional-location`. Таблица использует точные ключи свойств; переменные окружения из `.env.example` связываются с соответствующими ключами Spring Boot.

| Транспорт | Свойства |
| --- | --- |
| Artemis | `iib.transports.artemis.broker-url` (`tcp://host:port`), `username`, `password`, `max-connections` (4), `max-sessions-per-connection` (32) с тем же префиксом |
| IBM MQ | `iib.transports.ibmmq.host`, `port` (1414), `queue-manager`, `channel`, `username`, `password`, `max-connections` (4), `max-sessions-per-connection` (32) |
| Kafka | `iib.transports.kafka.bootstrap-servers`, `client-id`, `send-timeout` (30s), `security-protocol` (PLAINTEXT), `sasl-mechanism`, `sasl-jaas-config` |

Общие поля TLS под префиксом `iib.transports.<transport>.tls`: `enabled`, `trust-store`, `trust-store-password`, `trust-store-type` (PKCS12), `key-store`, `key-store-password`, `key-store-type` (PKCS12), `key-password`. Пути указываются в файловой системе процесса; для контейнера хранилища нужно смонтировать.

Для Artemis включите `tls.enabled=true`; URL сохраняет схему `tcp://`, проверка имени хоста включена. Пароль ключа должен совпадать с паролем keystore. Для IBM MQ дополнительно задайте `iib.transports.ibmmq.cipher-suite` и при необходимости `ssl-peer-name`, согласованные с каналом брокера. Без явных хранилищ используются JVM JSSE настройки; явный truststore сохраняет JVM client keystore для mTLS.

Для Kafka TLS определяется `security-protocol=SSL` или `SASL_SSL`; свойство `tls.enabled` не заменяет выбор протокола. SASL использует `sasl-mechanism` и внешнее `sasl-jaas-config`. Потребители по умолчанию имеют `autoCommitEnable=false`, `allowManualCommit=true`, `breakOnFirstError=true`, `autoOffsetReset=earliest`. Для новой consumer group `earliest` читает доступную историю; имя группы и политика старта должны быть выбраны при миграции. Не переопределяйте ручной commit в маршруте без согласования с inbox.

JMS отправитель публикует persistent `BytesMessage`, Kafka — исходные байты. Поддерживаемые типы `MessageHeader`: STRING (UTF-8), BYTES, BOOLEAN (0/1), BYTE, SHORT, INT, LONG, FLOAT, DOUBLE (числа в сетевом порядке байтов). Kafka допускает повторяющиеся имена заголовков. JMS application properties требуют уникальных имён и скалярных значений; `JMSCorrelationID` поддерживает STRING/BYTES. Заголовок `applicationEventId` зарезервирован отправителем. Совместимость конкретных JMS типов, MQMD/MQRFH2, TTL и кодировки с внешним потребителем задаётся адаптером модуля и проверяется отдельно.

Самостоятельный runtime проверен на локальных Artemis и Kafka без TLS. Для IBM MQ проверены создание клиента, независимая конфигурация и общий JMS-контракт; реальный IBM MQ и TLS handshake требуют доступного стенда и сертификатов.
