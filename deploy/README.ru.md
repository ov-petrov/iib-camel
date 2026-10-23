# Локальный пилот: Artemis

Решение пользователя от 2026-10-22: основной брокер пилота — **Apache Artemis**. IBM MQ остаётся опциональным адаптером и не блокирует пилот. JMS-свойства в Artemis не доказывают перенос IBM MQMD; совместимость с существующими IBM MQ системами проверяется отдельно.

## Запуск

Пилот использует внешний TCP-брокер с persistent volume. Профиль `local`, включённый по умолчанию, запускает непостоянный embedded Artemis только для разработки.

1. Скопировать `deploy/artemis/.env.example` в `deploy/artemis/.env`, задать `ARTEMIS_PASSWORD` вне Git. Файл должен быть доступен только владельцу: `chmod 600 deploy/artemis/.env`.
2. Из корня проекта запустить:

```bash
docker compose --env-file deploy/artemis/.env -f deploy/artemis/compose.yaml up -d --wait
set -a
source deploy/artemis/.env
set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn verify
ARTEMIS_INTEGRATION=true mvn -pl runtime -am test \
  -Dtest=ArtemisDeliveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
java -jar runtime/target/iib-camel-runtime-0.0.1-SNAPSHOT.jar --spring.profiles.active=artemis
```

`JAVA_HOME` выше — пример для macOS; на других ОС требуется установленный JDK 25. `ARTEMIS_BROKER_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `ARTEMIS_MAX_CONNECTIONS`, `ARTEMIS_MAX_SESSIONS`, `ARTEMIS_POOL_WAIT_TIMEOUT` задаются окружением. Стенд слушает только `127.0.0.1:61626`, использует официальный `apache/artemis:2.53.0-alpine`, поддерживающий amd64 и arm64. Брокерные пользователи создаются при первом запуске volume; изменение пароля окружения не обновляет уже созданную учётную запись.

Остановка с сохранением журнала:

```bash
docker compose --env-file deploy/artemis/.env -f deploy/artemis/compose.yaml stop
```

`down` сохраняет named volume; `down -v` удаляет данные и применяется только при намеренном сбросе локального стенда. Настройки в `etc-override` копируются при создании экземпляра. Для изменения существующего экземпляра нужно отдельно обновить его конфигурацию или создать новый volume.

## Контракт доставки

- Поддерживается синхронный **InOnly queue → Camel/direct → queue** на одной `ConnectionFactory`. `JmsTransactionManager` объединяет приём и отправки этого брокера в локальную JMS-транзакцию. Reply-to автоматически не исполняется. Startup-проверка отклоняет JMS-потребителей с `transacted=false`, topic и включённым reply-to, чтобы endpoint override не ослаблял ACK-контракт молча. HTTP, БД, другой брокер и асинхронные ветви в эту транзакцию не входят. XA и exactly-once не заявляются.
- Необработанная ошибка откатывает приём и все JMS-отправки текущей попытки. Повторяется весь маршрут с исходным брокерным сообщением. Уже исполненные внешние операции могут повториться, поэтому требуют идемпотентности.
- `IIB_JMS_MAXIMUM_REDELIVERIES=2` означает первую бизнес-попытку плюс два повтора. На **следующей**, четвёртой доставке бизнес-маршрут не запускается: исходное сообщение направляется в `IIB_JMS_DLQ` (по умолчанию `IIB.DLQ`) в одной транзакции с приёмом. Так последняя неуспешная бизнес-попытка также откатывает все свои отправки.
- `IIB_JMS_REDELIVERY_DELAY=1000` — задержка в миллисекундах перед повторной доставкой, включая повтор отправки в DLQ. `IIB_JMS_CONSUMERS=1` — число параллельных потребителей. Все параметры доступны также как `iib.messaging.*`.
- Если отправка в DLQ не удалась, вход остаётся неподтверждённым, транзакция откатывается и карантин повторяется до восстановления DLQ. Бизнес-логика при этом повторно не исполняется.
- Успешный локальный `doCatch`/failure-обработчик считается обработанным результатом и коммитится. Ошибка самого обработчика выходит к JMS-потребителю, откатывает его транзакцию и подчиняется общей политике. Внутренний `direct:` сохраняет транзакцию JMS-корня; отдельной брокерной попытки для него нет.
- На входе число доставок и исходный ID сохраняются в exchange properties `iib.jms.deliveryCount` и `iib.jms.messageId`. Сброс/изменение бизнес-заголовков Compute не меняет решение о повторе.
- DLQ сохраняет исходное текстовое XML-тело, поддерживаемые JMS application properties, correlation ID, priority, persistence и expiration. Добавляются `IibOriginalMessageId`, `IibOriginalDestination`, `IibDeliveryCount`. Новый JMS send создаёт новый `JMSMessageID`, `JMSTimestamp`, `JMSDestination` и брокерные delivery headers; они не обещаются побитово одинаковыми. Исходная причина ошибки записывается в журнал, но не переносится через rollback в следующее сообщение.
- Исходный TTL сохраняется: просроченные сообщения могут истечь до карантина или уже в DLQ. Счётчик `JMSXDeliveryCount` зависит от провайдера и может терять точность после аварии; это ограниченный retry-контракт, не гарантия точного количества попыток после любых сбоев.
- Политикой карантина владеет приложение. В конфигурации Artemis задаётся `max-delivery-attempts=-1`; иные брокерные лимиты могут перехватить сообщение раньше приложения. DLQ не должна одновременно быть входом того же маршрута. Ручные route-level error handlers и endpoint overrides требуют отдельной проверки контракта.

`JmsDeliveryPolicyTest` проверяет настоящий rollback/redelivery на embedded Artemis. `ArtemisDeliveryIntegrationTest` повторяет тот же контракт на внешнем persistent Artemis через Spring Boot `artemis`-профиль: ошибки после отправки, успешный повтор, локальный обработчик, ошибка обработчика, `direct:` и временный отказ отправки в карантин. Последний отказ внедряется в producer до JMS send; утверждения проверяют реальные rollback и повторные доставки брокера.

## Опциональный IBM MQ

`ibm-mq` использует официальный Jakarta-клиент `com.ibm.mq.jakarta.client:9.4.5.1`, клиентский транспорт, MQCSP auth, TLS cipher suite/peer name и пул соединений/сессий. IBM объявляет поддержку Java 25 начиная с MQ 9.4.5. Отсутствующие обязательные host/queue-manager/channel и неверные port/pool limits приводят к ошибке создания конфигурации. `IbmMqConfigTest` проверяет настоящий объект IBM client и Spring binding без соединения с сервером. Сквозное поведение IBM MQ **не считается проверенным** до успешного gated-теста.

Для отдельного developer-стенда скопировать `deploy/ibm-mq/.env.example` в `.env`, задать пароли app/admin и тот же пароль app в `IBM_MQ_PASSWORD`, ознакомиться с лицензией и установить `IBM_MQ_LICENSE=accept`:

```bash
docker compose --env-file deploy/ibm-mq/.env -f deploy/ibm-mq/compose.yaml up -d --wait
set -a
source deploy/ibm-mq/.env
set +a
IBM_MQ_INTEGRATION=true mvn -pl runtime -am test \
  -Dtest=IbmMqDeliveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
java -jar runtime/target/iib-camel-runtime-0.0.1-SNAPSHOT.jar --spring.profiles.active=ibm-mq
```

Параметры `IBM_MQ_HOST`, `IBM_MQ_PORT`, `IBM_MQ_QUEUE_MANAGER`, `IBM_MQ_CHANNEL`, `IBM_MQ_USER`, `IBM_MQ_PASSWORD`, `IBM_MQ_SSL_CIPHER_SUITE`, `IBM_MQ_SSL_PEER_NAME`, `IBM_MQ_MAX_CONNECTIONS`, `IBM_MQ_MAX_SESSIONS`, `IBM_MQ_POOL_WAIT_TIMEOUT` задаются окружением. Trust/key stores TLS задаются JVM JSSE, например `javax.net.ssl.trustStore`; пароли хранилищ не размещаются в Git. Для этого локального стенда DLQ `IIB.DLQ`, тестовые очереди `DEV.IIB.*`, queue manager `QM1`, channel `DEV.APP.SVRCONN`, порт `21414`. MQSC задаёт `BOTHRESH(0)`: poison routing IBM client не должен опередить приложение. Для существующих очередей это условие согласуется с MQ-администратором.

Официальный IBM image `icr.io/ibm-messaging/mq:9.4.5.1-r1` не содержит arm64: на Apple Silicon stand использует `linux/amd64` через эмуляцию Docker. Это developer-конфигурация, а не подтверждение производительности/поддерживаемости production IBM MQ на arm64.

## Проверка 2026-10-22

На JDK 25 выполнены 3 проверки IBM client/config без сервера, 7 проверок embedded Artemis и 7 проверок внешнего Artemis 2.53.0 через native Spring Boot профиль: все успешны. Внешний контейнер запущен на arm64 с persistent volume. При запуске из ограниченного sandbox потребовалось разрешение на локальный TCP; это ограничение среды запуска тестов. IBM MQ сквозные тесты не запускались после выбора Artemis основным брокером.

## Источники

- [Camel 4.22 JMS: транзакции и пулы](https://camel.apache.org/components/4.22.x/jms-component.html)
- [Camel: общая конфигурация маршрутов](https://camel.apache.org/manual/route-configuration.html)
- [Spring Boot: Artemis и connection pool](https://docs.spring.io/spring-boot/reference/messaging/jms.html)
- [Artemis: официальный Docker image и persistent instance](https://artemis.apache.org/components/artemis/documentation/latest/docker.html)
- [Jakarta Messaging: JMSXDeliveryCount и границы точности](https://jakarta.ee/specifications/messaging/3.1/jakarta-messaging-spec-3.1.html)
- [IBM MQ 9.4.5: Java 25](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=945-whats-changed-in-mq)
- [IBM: poison messages и BOTHRESH/BOQNAME](https://www.ibm.com/docs/SSFKSJ_9.2.0/com.ibm.mq.dev.doc/q032280_.html)
- [IBM: developer image и доступные архитектуры](https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-mq-advanced-developers-container-image)
- [IBM container: пароли через secrets](https://github.com/ibm-messaging/mq-container/blob/master/docs/pluggable-connauth.md)
