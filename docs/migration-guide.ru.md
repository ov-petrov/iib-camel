# Пошаговая инструкция по миграции IIB → Spring Boot + Apache Camel

> **Архивное руководство по compatibility-тестовой среде.** Шаги деплоя и запуска ниже относятся к `compatibility-tests/legacy-runtime` на Java 25; это не инструкция для самостоятельного runtime. Новый runtime использует Java 21, Spring Boot 4.1.2, Apache Camel 4.22.2 LTS и Jackson 3 XML. Его сборка, настройки PostgreSQL inbox/outbox, offline-профиль и подключение модулей описаны в [руководстве runtime](../runtime/README.ru.md), Compose-стенд — в [runtime/deploy](../runtime/deploy/compose.yaml).

> Руководство для заказчика: как с нуля превратить артефакты IBM Integration Bus
> (потоки сообщений `.msgflow`, вычисления `.esql`) в маршруты Apache Camel
> и запустить их в runtime-сервисе на Spring Boot.
>
> Продукт состоит из двух компонентов:
> * **converter** — one-shot CLI (Java, picocli): парсит IIB-артефакты, строит
>   промежуточную модель (IR), генерирует Camel XML DSL и отчёт о миграции;
>   после завершения работы завершается (никаких фоновых процессов);
> * **runtime** — сервис Spring Boot 4 + Apache Camel 4, который фактически
>   исполняет мигрированные маршруты.
>
> Функциональные требования: `docs/functional-requirements.ru.md`;
> стратегия тестирования: `docs/test-strategy.ru.md`.

---

## Шаг 0. Требования к окружению

| Компонент | Версия | Примечание |
|---|---|---|
| Java (JDK) | 25 | converter и legacy runtime для compatibility-тестов |
| Apache Maven | 3.9+ | сборка `mvnw`-обёртка не поставляется — используйте локальный Maven |
| Git | — | клонирование репозитория |
| Брокер JMS | встроенный Artemis (из коробки) или внешний | встроенный broker стартует вместе с runtime |

> Установка JDK 25 (Linux/macOS, Homebrew): `brew install openjdk@25`.
> Убедитесь, что `java -version` и `mvn -version` показывают JDK 25.

Проверка окружения:

```bash
java -version
mvn -version
```

---

## Шаг 1. Получение и сборка проекта

```bash
git clone <URL_репозитория> iib-camel
cd iib-camel
mvn -B -f pom.xml -pl converter,compatibility-tests/legacy-runtime -am verify
```

Число тестов зависит от текущей версии; ориентируйтесь на результат Maven для этой ревизии.

После этого артефакты готовы к использованию:

```text
converter/target/converter.jar                            ← CLI-конвертер (fat-jar, самодостаточный)
compatibility-tests/legacy-runtime/target/iib-camel-legacy-runtime-tests-0.0.1-SNAPSHOT.jar ← legacy boot-jar для compatibility-проверок
```

> `converter.jar` — самодостаточный (зависимости внутри): его можно копировать
> в любой каталог и запускать как `java -jar converter.jar` — сопровождающий
> каталог `lib/` не нужен.

---

## Шаг 2. Подготовка исходных IIB-артефактов

Соберите из IIB/ACE каталог с исходниками. Минимальный состав на один поток:

```text
input/
├── Adapter.msgflow              ← поток сообщений (EMF/XMI-формат IIB)
└── Adapter_Compute1.esql        ← ESQL-модули Compute, на которые ссылается поток
```

Требования к содержимому:

* в `.msgflow` узлы имеют `ComIbm*.msgnode:FCMComposite_1`-формат — это штатный вид
  файлов из IBM Toolkit, дополнительной подготовки не требуется;
* в `.esql` обязателен заголовок `BROKER SCHEMA <schema>` и модули вида
  `CREATE COMPUTE MODULE <Имя>`; функция извлечения привязки к маршруту —
  `computeExpression="esql://routine/<schema>#<Module>.Main"` внутри .msgflow;
* имена входных/выходных очередей MQ совпадают с теми, что указаны в атрибуте
  `queueName` узлов MQInput/MQOutput.

Для быстрой проверки можно взять готовый тестовый fixture из репозитория:

```bash
cp -r converter/src/test/resources/corpus/corr-01-simple-adapter input
```

---

## Шаг 3. Инвентаризация «как есть» (необязательно, но полезно)

Перед конвертацией убедитесь, что конвертер видит ожидаемую топологию, и получите
оценку готовности системы к миграции:

```bash
java -jar converter/target/converter.jar \
  analyze --input input --output diagrams --format all
```

Результат (три артефакта на поток):

- `diagrams/Adapter.mmd` — плоская диаграмма Mermaid (для отображения вставьте её
  в [mermaid.live](https://mermaid.live) или любой md-редактор);
- `diagrams/Adapter.adoc` — AsciiDoc-документ с таблицами узлов и связей;
- `diagrams/Adapter.md` — отчёт «как есть»: встроенная схема Mermaid, таблица узлов
  с пометкой, предусмотрена ли конвертация каждого типа, и вердикт
  «Готовность к миграции» (обработка всех узлов IIB — да/нет, какие типы
  потребуют ручной миграции или расширения правил).
- `diagrams/esql-inventory.md` — системный инвентарь ESQL (2026-10): по всем
  `.esql`-файлам входа — модули, функции, операторы `SET` и вердикт переводимости по
  подмножеству FR-3.2 (переводимо полностью / частично с числом переведённых операторов /
  вне подмножества). Инвентарь не создаёт артефактов конвертации (бины, модели) — это
  оценка объёма ручной работы **до** Шага 4; детальные причины видны после конвертации
  в отчёте convert.

> Если в вердикте «нет» — часть типов узлов потока вне базового набора правил
> (FR-2.2): такие узлы конвертер заменит заглушкой и диагностикой в отчёте либо их
> придётся мигрировать вручную (FR-2.7). Это видно до конвертации.

Пакетная обработка каталога — та же команда с `--input <каталог>` вместо файла.

> Дефолты: если флаги не указаны, вход берётся из `./input`, а артефакты пишутся
> в `./output` (обе папки создаются при отсутствии — относительно каталога,
> из которого запущен jar). Без `--format` рендерятся **все три формата** сразу
> (`--format all`); доступны также `--format mermaid|adoc|md`.

---

## Шаг 4. Конвертация в маршруты Camel

Основная команда конвертации (FR-2.1: парсер → генератор → вывод + отчёт):

```bash
java -jar converter/target/converter.jar \
  convert --input input --output converted
```

> Дефолты: `--input ./input`, `--output ./output` (создаётся при отсутствии;
> относительно каталога, из которого запущен jar). Явные флаги, как выше,
> переопределяют дефолт.

Параметры:

| Параметр | Значение | Назначение |
|---|---|---|
| `--input`, `-i` | файл или каталог | `.msgflow`-файл(ы); каталог обходится рекурсивно |
| `--output`, `-o` | каталог | результат; по умолчанию `converted` |

Повторный запуск в том же output использует `.iib-camel-convert.tsv` с контрольными
суммами. Изменённые вручную и посторонние файлы не перезаписываются; старые неизменённые
generated-артефакты удаляются, если больше не входят в результат. Для старого вывода без
manifest безопаснее выбрать новый каталог. При коллизиях имён сначала исправьте ошибки
из отчёта: пакет маршрутов/классов не публикуется.

Результат:

```text
converted/
├── Adapter-route.xml      ← сгенерированный маршрут (Camel XML DSL)
└── migration-report.md    ← отчёт о миграции (FR-5.1)
```

Пример сгенерированного маршрута (из тестового fixture):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<routes xmlns="http://camel.apache.org/schema/spring">
  <route id="Adapter">
    <from uri="jms:ADAPTER.IN"/>
    <doTry>
      <to uri="bean:adapterCompute1"/>
      <choice>
        <when>
          <xpath>/Adapter/Order/status='HIGH'</xpath>
          <to uri="jms:ADAPTER.PRIORITY.Q"/>
        </when>
        <otherwise>
          <to uri="jms:ADAPTER.DEFAULT.Q"/>
        </otherwise>
      </choice>
      <doCatch>
        <exception>java.lang.Exception</exception>
        <to uri="jms:ADAPTER.ERROR.Q"/>
      </doCatch>
    </doTry>
  </route>
</routes>
```

Связь с исходными узлами IIB и причины ручной миграции сохраняются в отчёте (FR-2.6).
Сгенерированные Java и XML не содержат поясняющих комментариев.

**Пример корректного вывода команды:**

```text
Конвертация: файлов 1 из input → converted; конвертировано 1, ошибок 0
```

Маппинг узлов IIB (FR-2.2, Must):

| Узел IIB | Результат Camel |
|---|---|
| `MQInput` | `<from uri="jms:<queueName>"/>` |
| `Compute` | `<to uri="bean:<имя_бинa>"/>` — идентификатор берётся из `esql://routine/#Module` |
| `Route` (filterTable) | XPath по XML-телу; `First` → choice, `All` → последовательные ветви с копиями исходного сообщения; порядок строк сохранён; failure отделён от ошибки downstream |
| `MQOutput` | `<to uri="jms:<queueName>"/>` |
| терминал `catch`/`failure` | `<doCatch>` → в очередь обработки ошибок (FR-2.3) |
| прочие (не поддержано) | TODO-заглушка `<log message="Unsupported IIB node: …"/>` (FR-2.7) + отметка в отчёте |

---

## Шаг 5. Чтение отчёта `migration-report.md` (FR-5.1) и принятие решений

Отчёт полный (FR-5.1–5.3). Структура:

* **Статусы единиц** — сводка по всем единицам конвертации (узлы `.msgflow` + модули ESQL):
  `конвертировано` / `частично` / `требует ручной доработки` / `не поддерживается` + проценты;
* **Потоки** — таблица: файл → сгенерированный маршрут, число узлов разбить по статусы;
* **Узлы по типам** — сколько узлов каждого типа, и в каком они статусе;
* **Детализация узлов** — по каждому узлу: id, тип, статус и примечание (цель элемента
  Camel или причина TODO — нет очереди, нет фильтра, узел вне пути, заглушка);
* **ESQL** — модули, переведённые SET и полные неподдерживаемые фрагменты;
  неполный бин останавливается до выполнения бизнес-операций;
* **Ошибки** — файлы, которые не удалось разобрать;
* **Сводка** — количество файлов и единиц, процент полноты автоматизации (FR-5.3).

Пример содержимого (фикстура corr-01):

```markdown
## Статусы единиц

| Статус | Единиц | % |
|---|---|---|
| конвертировано | 7 | 100.0 |
| требует ручной доработки | 0 | 0.0 |
| **Всего** | 7 | 100.0 |

## Потоки

| Поток | Результат | Узлов | Конв. | Част. | Руч. | Не под. |
|---|---|---|---|---|---|---|
| Adapter.msgflow | [`Adapter-route.xml`](Adapter-route.xml) | 6 | 6 | 0 | 0 | 0 |

## Сводка
- Файлов msgflow: 1 (конвертировано 1, с ошибками разбора 0)
- Требуют внимания: 0 из 7 единиц; полнота автоматизации: 100.0% конвертировано (FR-5.3)
```

Решение после того как просмотрели отчёт:

* «Требуют внимания: 0» → переходите к шагу 6 (деплой маршрута в runtime);
* значения > 0 → смотрите разделы «Детализация узлов» и «Операторы ESQL»:
  там перечислены конкретные узлы/операторы со статусами
  `частично` (требует доработки; неполный ESQL-бин выбрасывает исключение при вызове),
  `требует ручной доработки` (код формируется вручную по FR-3.x)
  и `не поддерживается` (расширьте правила FR-2.2 или передайте эксперту).

> Правило: **не удаляйте** маршрут из артефакта, если есть unsupported-узлы
> — маршрут остаётся в миграционном наборе, пока ручная миграция не завершена.

---

## Шаг 6. Деплой маршрута в legacy compatibility runtime

Скопируйте XML-маршрут в каталог `camel/` ресурсов runtime-сервиса:

```bash
mkdir -p compatibility-tests/legacy-runtime/src/main/resources/camel
cp converted/Adapter-route.xml compatibility-tests/legacy-runtime/src/main/resources/camel/
```

Паттерн загрузки legacy runtime задан в `compatibility-tests/legacy-runtime/src/main/resources/application.properties`:

```properties
camel.main.routes-include-pattern = classpath:camel/*.xml
```

ⓘ Camel берёт «расширение» файла как всё после **первой** точки, поэтому
маршруты называются `<Имя>-route.xml` (именно так их пишет `convert`): только
символ `-` не образует «двойного» расширения, и стандартный XML-лоадер
подбирается автоматически, без дополнительной регистрации.

### 6.1. Compute-бины

Конвертер создаёт `beans/<Class>.java` в пакете
`ru.petrovov.iib_camel.runtime.generated`. В базовом примере используются
сгенерированные `AdapterCompute1` и `CheckCompute1`; ручной POJO-парсер не требуется.

```bash
mkdir -p compatibility-tests/legacy-runtime/src/main/java/ru/petrovov/iib_camel/runtime/generated
cp converted/beans/*.java compatibility-tests/legacy-runtime/src/main/java/ru/petrovov/iib_camel/runtime/generated/
```

Бины наследуют `AbstractComputeBean`. Единственная точка вызова — `compute(Exchange)`.
`copy()` восстанавливает тело и заголовки на входе в текущий Compute; снимок рассчитан
на строковое тело и скалярные значения заголовков. `mqmd()` устанавливает JMS-свойство,
что ещё не доказывает поддержку реального поля IBM MQMD. `setBodyField()` изменяет XML:
путь после XMLNSC включает корень, например `Order.Status` для `<Order><Status>…`.

Полностью переводится ограниченное прямолинейное подмножество: копирование,
поддерживаемые строковые SET и завершающий `RETURN TRUE`. IF/CASE/циклы,
`RETURN FALSE`, PROPAGATE и неизвестные конструкции сохраняются для ручной доработки.
**Неполный бин выбрасывает `UnsupportedOperationException` до любых присваиваний**.
Пооператорные статусы показывают распознанные операции, но не разрешают запускать
часть бизнес-логики отдельно от неподдерживаемого блока.

Для ручной реализации используйте собственный наследник `AbstractComputeBean`,
сохраняя ожидаемое имя Spring-бина. В приложении должен существовать один бин с этим
именем. Исправления generated-файлов пока следует сохранять отдельно перед регенерацией.

### 6.2. XML-тело и модели

Route использует XPath по строковому XML-телу. Сохраняются строки filterTable,
их порядок и связь с терминалами. `First` выбирает первое совпадение, `All` (по умолчанию)
отправляет копию по каждой совпавшей строке. Default выполняется при отсутствии совпадений.
Failure Route относится только к вычислению условий; исключение бизнес-ветки идёт
во внешний catch. Полные цепочки обработчиков могут содержать Compute и другие
поддерживаемые шаги. Изменения сообщения при ошибке откатываются к входу области
обработки через MessageSnapshots, поставляемый в runtime.
Неподдерживаемое выражение явно отклоняется; переход в Default вместо него не выполняется.

Пока поддерживаются простые XML-пути без namespaces; JSON, namespace-префиксы
и индексированные ESQL-пути требуют отдельной реализации. XML с DOCTYPE отклоняется.
Модели из `models/` остаются предварительными заготовками: поля String,
полного ULM и MapStruct пока нет. Для исполнения базового Route они не требуются.

---

## Шаг 7. Запуск legacy runtime для compatibility-проверки

Boot-jar стартует автономно: встроенный Artemis поднимается вместе с сервисом:

```bash
java -jar compatibility-tests/legacy-runtime/target/iib-camel-legacy-runtime-tests-0.0.1-SNAPSHOT.jar
```

Ожидаемый лог:

```text
Routes startup (total:1)
    Started Adapter (jms://ADAPTER.IN)
Apache Camel 4.22.0 (camel-1) started ... (build/init:0ms start:…)
Started IibCamelApplication in … seconds
```

Ctrl+C останавливает сервис.

> Для проверки из Maven: `mvn -pl converter,compatibility-tests/legacy-runtime -am test`. Новый runtime запускается по отдельной инструкции из `runtime/README.ru.md`.

**Подключение к внешнему брокеру (FR-R3.x):**

```bash
java -Dspring.artemis.mode=NATIVE \
     -Dspring.artemis.host=mq.example.com \
     -Dspring.artemis.port=61616 \
     -Dspring.artemis.user=<***> \
     -Dspring.artemis.password=<***> \
     -jar compatibility-tests/legacy-runtime/target/iib-camel-legacy-runtime-tests-0.0.1-SNAPSHOT.jar
```

Пара важных настроек JMS-компонента встроена в код/конфигурацию:

| Настройка | Где | Аналог в IIB |
|---|---|---|
| `camel.component.jms.preserve-message-qos=true` | `application.properties` | копирование `MQMD.Priority` |
| `jmsMessageType=Text` + XML-renderer в `toString()` по модели | `runtime/config/CamelMessagingConfig.java`, `runtime/model/Adapter.java` | перенос `OutputRoot` как XML-сообщения |

---

## Шаг 8. Сквозная проверка миграции (e2e, уровень L3)

Тестовые сценарии для потока хранятся в декларативном датасете `dataset.yml`
(структура в `compatibility-tests/legacy-runtime/src/test/resources/e2e/corr-01-simple-adapter/`).
Проверка корректности маршрута на реальном исполнении (встроенный Artemis):

```bash
mvn -pl converter,compatibility-tests/legacy-runtime -am test -Dtest=E2eDatasetTest,GeneratedArtifactsTest,GeneratedRouteSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Проверяются сохранённые и свежесгенерированные маршруты; базовый датасет содержит три кейса:
высокий приоритет → PRIORITY.Q, обычный → DEFAULT.Q, битое XML → ERROR.Q.

### 8.1. Ручная проверка (одноразовая, без тестов)

Через `activemq`-артефакт или любой JMS-клиент отправьте в `ADAPTER.IN`:

```xml
<Adapter>
  <Order>
    <id>ORD-9001</id>
    <status>HIGH</status>
    <amount>17.50</amount>
  </Order>
</Adapter>
```

Ожидание: сообщение появится в `ADAPTER.PRIORITY.Q` (при priority=8),
тип — `TextMessage`, содержимое — исходный XML, свойство
`ApplicationIdData=IIB-CAMEL-MIG`.

---

## Шаг 9. Чек-лист миграции нового потока

1. [ ] Собраны `.msgflow` + все упоминаемые `.esql` в каталог `input/`.
2. [ ] `analyze` → топология соответствует ожиданиям, вердикт «Готовность к миграции»
       — «да» либо известен план по узлам вне покрытия (Шаг 3).
3. [ ] `convert` → `converted/<Имя>-route.xml` + отчёт (Шаг 4).
4. [ ] В отчёте «не поддерживается: 0» либо за mark вручную сформулирован план
       доработки unsupported-узлов (FR-2.7) (Шаг 5).
5. [ ] Маршрут скопирован в `compatibility-tests/legacy-runtime/src/main/resources/camel/` (Шаг 6).
6. [ ] Для каждого Compute/бboss-узла реализован `@Component`-бин с camelcase-имя
       модуля (Шаг 6.1) и POJO-модель (Шаг 6.2).
7. [ ] Датасет e2e заполнен сценариями (happy path + работоспособность приоритетов
       + некорректное сообщение) и тестирован (Шаг 8).
8. [ ] Проверено поведение с внешним брокером, если в цели — не с embedded (Шаг 7).

---

## Приложение А. Коды возврата CLI и диагностика

Обе подкоманды (`analyze`, `convert`) возвращают:

| Код | Значение |
|---|---|
| `0` | полная обработка всех входных файлов |
| `2` | частичная обработка (есть ошибки разбора, см. stderr и отчёт) |
| `3` | ни один файл не обработан / входы не найдены |

Типовые проблемы:

| Симптом | Причина | Действие |
|---|---|---|
| `.msgflow-файлы не найдены` (код 3) | неверный `--input`, нет `.msgflow` внутри | проверьте путь и расширения файлов |
| Ошибка разбора сообщения в отчёте | нетиповой XMI (`computeExpression` отсутствует) | сверите узлы Compute с Toolkit; добавьте вычисления `esql://routine/#Module` |
| В ожидаемой очереди «нет сообщения» | бин Compute выбросил исключение (например,регистр в XML) → сработал `doCatch` | проверьте логи runtime: предупреждение/unmarshalling, свойство `ApplicationIdData` |
| Сообщение в ошибке, но XML валиден | Jackson XML регистрозависим к именам элементов | пометьте модель `@JacksonXmlProperty(localName="Order")` (Шаг 6.2) |
| Приоритет не перенесён (получатель видит 4) | потеря QoS при пересоздании сообщения | используйте дефолтную конфигурацию `CamelMessagingConfig` (preserve-message-qos + перенос приоритета), не переопределяйте `explicitQosEnabled` |
| Маршрут не загружается, bean не найден | имя файла маршрута с несколькими точками (например `X.route.xml`) | называйте маршруты `<Имя>-route.xml` (см. Шаг 6) |

## Приложение Б. Ответственность после миграции (обязанности заказчика)

* конвертер покрывает базовый набор узлов (FR-2.2 Must). Для остальных — расширяйте
  правила генерации в `converter/src/main/java/ru/petrovov/iib_camel/converter/camel/RouteGenerator.java`
  (ветка `walk`/`emitChoice`) — тесты на golden-файлы помогут;
* по мере добавления новых правил обновляйте golden-файл `expected/<Имя>-route.xml`
  (первое утверждение — фикстур, см. `docs/test-strategy.ru.md`, 3.2);
* отчёт FR-5.1 при этом становится историей миграции: keep до закрытия проекта
  перешеcтрования всех потоков.
