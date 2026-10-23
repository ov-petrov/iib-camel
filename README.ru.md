# iib-camel

Репозиторий объединяет инструмент анализа потоков IBM Integration Bus (IIB/ACE) и конвертации поддерживаемого
подмножества, а также самостоятельный Spring Boot и Apache Camel runtime для интеграций, переносимых вручную.

> [English version](README.md)

## Возможности

Команда `analyze` читает `.msgflow`, `.subflow` и `.esql`, создаёт диаграммы и отчёты по потокам, а при наличии ESQL — инвентарь модулей. Команда `convert` генерирует XML-маршруты Camel, Java-бины для поддерживаемого подмножества ESQL и отчёт о конвертации.

Подмножество ESQL ограничено: поддерживаются отдельные функции Compute `Main`, операции `SET`, условные ветви и выражения с явными преобразованиями типов. Неподдерживаемый или неполный ESQL отмечается в отчёте; сгенерированный бин останавливается до выполнения бизнес-операций. Для подмножеств XSD и JSON Schema генерируются модели; простые перемещения однотипных XMLNSC-полей из IBM `.map` генерируются как MapStruct-мапперы и runtime-процессоры. Это не полная поддержка ESQL, ULM или IBM Mapping. Команда `convert` по умолчанию включает строгую проверку готовности пакета. Подробности и ограничения приведены в документе [ход реализации](docs/implementation-progress.ru.md), [профиле пилота](docs/pilot-profile.ru.md) и [руководстве по развёртыванию](deploy/README.ru.md).

Это экспериментальный инструмент миграции, а не полный парсер IIB/ACE и не промышленный сервис миграции. Результаты нужно проверить относительно исходного приложения и целевой среды. Конвертер и runtime — независимые Maven-проекты с разными требованиями к JDK. Устаревший runtime сохранён только для compatibility-тестов.

## Архитектура

В репозитории находятся два независимых проекта. Конвертер на Java 25 анализирует входы IIB/ACE и генерирует артефакты
для поддерживаемого подмножества. Runtime на Java 21 — отдельное приложение Spring Boot и Camel для интеграций,
собираемых вручную; сгенерированные конвертером артефакты не загружаются в него автоматически. Бизнес-поведение, включая
преобразования ESQL, нужно проверить и реализовать в модулях runtime. Примерный модуль только передаёт байты сообщения
дальше.

Корневой Maven reactor включает конвертер, runtime и проект compatibility-тестов. У runtime также есть собственный
родительский POM и reactor в каталоге `runtime/`, который задаёт требования к Java 21 и управляет модулями.

| Модуль                                | Ответственность                                                                                         |
|---------------------------------------|---------------------------------------------------------------------------------------------------------|
| `converter/`                          | CLI на Java 25 для анализа IIB/ACE и конвертации поддерживаемого подмножества.                          |
| `runtime/runtime-core/`               | Camel-транспорты, поддержка XML и конверты сообщений на Java 21.                                        |
| `runtime/runtime-delivery/`           | Сервисы и хранение inbox/outbox в PostgreSQL.                                                           |
| `runtime/modules/example-module/`     | Пример Java-обработчика и вручную заданного Camel XML-маршрута; передаёт байты сообщения без изменений. |
| `runtime/runtime-app/`                | Исполняемое приложение Boot, связывающее библиотеки runtime и модули интеграций.                        |
| `compatibility-tests/legacy-runtime/` | Тестовое окружение на Java 25 только для регрессионных проверок артефактов конвертера.                  |
| `examples/analyze/`, `docs/`          | Иллюстративные входы для анализа, требования и руководство по миграции.                                 |

```mermaid
flowchart TB
    subgraph converter_project["Конвертер · Java 25"]
        direction TB
        sources["Входы IIB / ACE"] --> cli["converter"]
        cli --> generated["Отчёты, Camel XML и Java"]
        legacy["compatibility-tests<br/>legacy-runtime"] -.-> cli
    end
    subgraph runtime_project["Runtime · Java 21"]
        direction TB
        app["runtime-app"]
        example["modules/example-module"]
        delivery["runtime-delivery"]
        core["runtime-core"]
        app -.-> example
        app -.-> delivery
        app -.-> core
        example -.-> delivery
        delivery -.-> core
    end
```

Пунктирные стрелки показывают Maven-зависимости. Между конвертером и runtime нет зависимости или автоматической передачи
артефактов. Описание модели модулей runtime, настроек, доставки и запуска приведено
в [руководстве runtime](runtime/README.ru.md).

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

## Документация

- [Функциональные требования](docs/functional-requirements.ru.md)
- [Стратегия тестирования](docs/test-strategy.ru.md)
- [Ход реализации](docs/implementation-progress.ru.md)
- [Ручная проверка анализа](examples/analyze/README.ru.md)

## Лицензия

Проект распространяется по [Apache License, Version 2.0](LICENSE). Информация об атрибуции приведена в [NOTICE](NOTICE).
