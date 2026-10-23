# iib-camel

Прототип для анализа потоков IBM Integration Bus (IIB) / IBM App Connect Enterprise (ACE) и конвертации поддерживаемого подмножества в XML-маршруты Apache Camel и Java.

> [English version](README.md)

## Возможности

Команда `analyze` читает `.msgflow`, `.subflow` и `.esql`, создаёт диаграммы и отчёты по потокам, а при наличии ESQL — инвентарь модулей. Команда `convert` генерирует XML-маршруты Camel, Java-бины для поддерживаемого подмножества ESQL и отчёт о конвертации.

Подмножество ESQL ограничено: поддерживаются отдельные функции Compute `Main`, операции `SET`, условные ветви и выражения с явными преобразованиями типов. Неподдерживаемый или неполный ESQL отмечается в отчёте; сгенерированный бин останавливается до выполнения бизнес-операций. Для подмножеств XSD и JSON Schema генерируются модели; простые перемещения однотипных XMLNSC-полей из IBM `.map` генерируются как MapStruct-мапперы и runtime-процессоры. Это не полная поддержка ESQL, ULM или IBM Mapping. Команда `convert` по умолчанию включает строгую проверку готовности пакета. Подробности и ограничения приведены в документе [ход реализации](docs/implementation-progress.ru.md), [профиле пилота](docs/pilot-profile.ru.md) и [руководстве по развёртыванию](deploy/README.ru.md).

Это экспериментальный инструмент миграции, а не полный парсер IIB/ACE и не промышленный сервис миграции. Результаты нужно проверить относительно исходного приложения и целевой среды. Конвертер и runtime — независимые Maven-проекты с разными требованиями к JDK. Устаревший runtime сохранён только для compatibility-тестов.

## Сборка

Требуются Maven 3.9 или новее, JDK 25 для конвертера и compatibility-тестов, JDK 21 для runtime.

Собрать конвертер и compatibility-тесты legacy runtime из корня репозитория:

```sh
mvn -B -f pom.xml -pl converter,compatibility-tests/legacy-runtime -am verify
```

Собрать самостоятельный runtime:

```sh
mvn -B -f runtime/pom.xml verify
```

Сборка создаёт JAR конвертера `converter/target/converter.jar`. Требования к новому runtime, запуск, offline-режим, доставка через PostgreSQL и настройки транспортов описаны в [руководстве runtime](runtime/README.ru.md).

## Примеры CLI

Проанализировать демонстрационные входы и создать отчёты Mermaid, AsciiDoc и Markdown:

```sh
java -jar converter/target/converter.jar analyze \
  --input examples/analyze/system \
  --output target/manual-analysis \
  --format all
```

Конвертировать тестовый корпус в XML-маршруты Camel и Java:

```sh
java -jar converter/target/converter.jar convert \
  --input converter/src/test/resources/corpus \
  --output target/converted
```

Результаты записываются в выбранный каталог. Перед использованием изучите отчёт и диагностику неподдерживаемых операций.

## Структура проекта

- `converter/` — одноразовый Java CLI для анализа и конвертации.
- `runtime/` — самостоятельное приложение Spring Boot 4.1.2 и Apache Camel 4.22.2 LTS для Java 21 с Jackson 3 XML. Inbox/outbox с PostgreSQL включён по умолчанию; планировщик отправителя требует явного включения, а коннекторы отключены без настройки. Профиль `offline` запускает приложение без PostgreSQL и коннекторов.
- `compatibility-tests/legacy-runtime/` — legacy runtime на Java 25 только для тестов совместимости конвертера.
- `examples/analyze/` — небольшие иллюстративные входы для команды `analyze`.
- `docs/` — требования, заметки по дизайну, руководство по миграции и статус реализации.

## Документация

- [Функциональные требования](docs/functional-requirements.ru.md)
- [Стратегия тестирования](docs/test-strategy.ru.md)
- [Ход реализации](docs/implementation-progress.ru.md)
- [Ручная проверка анализа](examples/analyze/README.ru.md)

## Лицензия

Проект распространяется по [Apache License, Version 2.0](LICENSE). Информация об атрибуции приведена в [NOTICE](NOTICE).
