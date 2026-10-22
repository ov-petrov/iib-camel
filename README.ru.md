# iib-camel

Прототип для анализа потоков IBM Integration Bus (IIB) / IBM App Connect Enterprise (ACE) и конвертации поддерживаемого подмножества в XML-маршруты Apache Camel и Java.

> [English version](README.md)

## Возможности

Команда `analyze` читает `.msgflow`, `.subflow` и `.esql`, создаёт диаграммы и отчёты по потокам, а при наличии ESQL — инвентарь модулей. Команда `convert` генерирует XML-маршруты Camel, Java-бины для поддерживаемого подмножества ESQL и отчёт о конвертации.

Текущее подмножество ESQL намеренно ограничено: функции `Main` без параметров, `RETURNS BOOLEAN`, поддерживаемые операции `SET` и завершающий `RETURN TRUE`. Неподдерживаемый или неполный ESQL отмечается в отчёте; сгенерированный бин останавливается до выполнения бизнес-операций. XML-профиль маршрутов обрабатывает проверенный набор узлов, фильтров, подпотоков и путей ошибок; неподдерживаемые или неоднозначные конструкции графа диагностируются. Подробности приведены в документе [ход реализации](docs/implementation-progress.ru.md) и руководстве по [ручной проверке analyze](examples/analyze/README.ru.md).

Это экспериментальный инструмент миграции, а не полный парсер IIB/ACE и не промышленный сервис миграции. Сейчас нет генерации MapStruct, разбора наборов сообщений и схем, полной трансляции ESQL или интеграции IBM MQ. Результаты нужно проверить относительно исходного приложения и целевой среды.

## Сборка

Требуются JDK 25 и Maven.

```sh
mvn -B verify
```

Сборка создаёт JAR конвертера: `converter/target/converter.jar`.

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
- `runtime/` — runtime на Spring Boot и Apache Camel для сгенерированных маршрутов и бинов.
- `examples/analyze/` — небольшие иллюстративные входы для команды `analyze`.
- `docs/` — требования, заметки по дизайну, руководство по миграции и статус реализации.

## Документация

- [Функциональные требования](docs/functional-requirements.ru.md)
- [Стратегия тестирования](docs/test-strategy.ru.md)
- [Ход реализации](docs/implementation-progress.ru.md)
- [Ручная проверка анализа](examples/analyze/README.ru.md)

## Лицензия

Проект распространяется по [Apache License, Version 2.0](LICENSE). Информация об атрибуции приведена в [NOTICE](NOTICE).
