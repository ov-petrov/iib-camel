# Пошаговая инструкция по миграции IIB → Spring Boot + Apache Camel

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
| Java (JDK) | 21/25 | используются и converter, и runtime |
| Apache Maven | 3.9+ | сборка `mvnw`-обёртка не поставляется — используйте локальный Maven |
| Git | — | клонирование репозитория |
| Брокер JMS | встроенный Artemis (из коробки) или внешний | встроенный broker стартует вместе с runtime |

> Установка JDK 25螺栓 (Linux/macOS, Homebrew): `brew install openjdk@25`.
> Убедитесь, что `java -version` и `mvn -version` показывают JDK 21+.

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
mvn clean test
```

Ожидаемый результат: `BUILD SUCCESS`; всего **57 тестов** (converter 55 + runtime 2).
Если тесты зелённые и сборка прошла — окружение корректно.

```bash
mvn -DskipTests package
```

После этого артефакты готовы к использованию:

```text
converter/target/iib-camel-converter-0.0.1-SNAPSHOT.jar   ← CLI-конвертер
runtime/target/iib-camel-runtime-0.0.1-SNAPSHOT.jar       ← runtime-сервис (boot-jar)
```

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

## Шаг 3. Построение диаграммы потока (необязательно, но полезно)

Перед конвертацией убедитесь, что конвертер видит ожидаемую топологию:

```bash
java -jar converter/target/iib-camel-converter-0.0.1-SNAPSHOT.jar \
  diagram --input input --output diagrams --format mermaid
```

Результат: `diagrams/Adapter.mmd` (плоская диаграмма Mermaid; для отображения —
вставьте её в [mermaid.live](https://mermaid.live) или любойadorable md-редактор).

Дополнительно доступны форматы `--format ascii` и `--format adoc` (дляDOC-отчётов);
пакетная обработка каталога — эти же команды с `--input <каталог>` вместо файла.

---

## Шаг 4. Конвертация в маршруты Camel

Основная команда конвертации (FR-2.1: парсер → генератор → вывод + отчёт):

```bash
java -jar converter/target/iib-camel-converter-0.0.1-SNAPSHOT.jar \
  convert --input input --output converted
```

Параметры:

| Параметр | Значение | Назначение |
|---|---|---|
| `--input`, `-i` | файл или каталог | `.msgflow`-файл(ы); каталог обходится рекурсивно |
| `--output`, `-o` | каталог | результат; по умолчанию `converted` |

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
  <!-- source: узел Input (MQInput) -->
  <route id="Adapter">
    <from uri="jms:ADAPTER.IN"/>
    <doTry>
      <!-- source: узел Enrich (Compute) -->
      <to uri="bean:adapterCompute1"/>
      <!-- source: узел RouteByStatus (Route) -->
      <choice>
        <when>
          <simple>${body.order.status} == 'HIGH'</simple>
          <!-- source: узел PriorityQueue (MQOutput) -->
          <to uri="jms:ADAPTER.PRIORITY.Q"/>
        </when>
        <otherwise>
          <!-- source: узел DefaultQueue (MQOutput) -->
          <to uri="jms:ADAPTER.DEFAULT.Q"/>
        </otherwise>
      </choice>
      <doCatch>
        <exception>java.lang.Exception</exception>
        <!-- source: узел ErrorQueue (MQOutput) -->
        <to uri="jms:ADAPTER.ERROR.Q"/>
      </doCatch>
    </doTry>
  </route>
</routes>
```

Каждый шаг снабжён комментарием `<!-- source: … -->` — это трассируемость
до исходного узла IIB (FR-2.6).

**Пример корректного вывода команды:**

```text
Конвертация: файлов 1 из input → converted; конвертировано 1, ошибок 0
```

Маппинг узлов IIB (FR-2.2, Must):

| Узел IIB | Результат Camel |
|---|---|
| `MQInput` | `<from uri="jms:<queueName>"/>` |
| `Compute` | `<to uri="bean:<имя_бинa>"/>` — идентификатор берётся из `esql://routine/#Module` |
| `Route` (filterTable) | `<choice><when><simple>…'; filterPattern → навигация POJO-свойства (FR-3.2)</simple><to .../></when><otherwise><to .../></otherwise></choice>` |
| `MQOutput` | `<to uri="jms:<queueName>"/>` |
| терминал `catch`/`failure` | `<doCatch>` → в очередь обработки ошибок (FR-2.3) |
| прочие (не поддержано) | TODO-заглушка `<log message="Unsupported IIB node: …"/>` (FR-2.7) + отметка в отчёте |

---

## Шаг 5. Чтение отчёта `migration-report.md` (FR-5.1) и принятие решений

Отчёт содержит: таблицу потоков (файл → артефакт, количество узлов,
число неподдерживаемых), предупреждения об узлах, требующих ручной миграции,
а также ошибки парсинга и сводку.

Пример содержимого:

```markdown
# Отчёт о миграции

| Поток | Результат | Узлы | Не поддерживается |
|---|---|---|---|
Adapter.msgflow | [`Adapter-route.xml`](Adapter-route.xml) | 6 | 0

## Сводка
- Файлов: 1 (конвертировано 1, с ошибками 0)
```

Решение после того как просмотрели отчёт:

* «Не поддерживается: 0» → переходите к шагу 6 (деплой маршрута в runtime);
* значения > 0 → в отчёте перечислены узлы (типы — BP в секции FR-2.7).
  Такие узлы нужно обработать вручную: либо расширить генератор (FR-2.2),
  либо отдаётся на эксперта;

> Правило: **не удаляйте** маршрут из артефакта, если есть unsupported-узлы
> — маршрут остаётся в миграционном наборе, пока ручная миграция не завершена.

---

## Шаг 6. Деплой маршрута в runtime

Скопируйте XML-маршрут в каталог `camel/` ресурсов runtime-сервиса:

```bash
mkdir -p runtime/src/main/resources/camel
cp converted/Adapter-route.xml runtime/src/main/resources/camel/
```

Паттерн загрузки задан в `runtime/src/main/resources/application.properties`:

```properties
camel.main.routes-include-pattern = classpath:camel/*.xml
```

ⓘ Camel берёт «расширение» файла как всё после **первой** точки, поэтому
маршруты называются `<Имя>-route.xml` (именно так их пишет `convert`): только
символ `-` не образует «двойного» расширения, и стандартный XML-лоадер
подбирается автоматически, без дополнительной регистрации.

### 6.1. Реализация Compute-бинoв (аналог ESQL-модулей, FR-3.1/FR-3.4)

Маршрут ссылается на бин `adapterCompute1` (имя camelize-ится из
`esql://routine/adapter#Adapter_Compute1.Main` → `adapterCompute1`).
Реализуйте его как `@Component` с тем же именем — по шаблону
`runtime/src/main/java/com/example/iib_camel/runtime/compute/AdapterCompute1.java`:

```java
@Component("adapterCompute1")
public class AdapterCompute1 {

    public Adapter compute(Exchange exchange) throws Exception {
        String xml = exchange.getIn().getBody(String.class);
        // 1. перенос сообщения как есть (IIB: SET OutputRoot = InputRoot)
        Adapter adapter = map/xml → модель (XmlMapper.readValue(xml, Adapter.class));
        // 2. аналог SET OutputRoot.MQMD.ApplicationIdData = 'IIB-CAMEL-MIG'
        exchange.getIn().setHeader("ApplicationIdData", "IIB-CAMEL-MIG");
        return adapter;
    }
}
```

Если ESQL-модуль содержит только `RETURN TRUE` (чистый проход) — достаточно
вернуть входное тело без изменений.

### 6.2. Модель сообщения (ULM, FR-3.4)

Создайте POJO по XSD-структуре входного XML: классы с геттерами (не record) —
simple-выражения (`${body.order.status}`) резолвятся через JavaBean-свойства.
Пример — `runtime/src/main/java/com/example/iib_camel/runtime/model/{Adapter,Order}.java`.

Используйте аннотацию `@JacksonXmlProperty(localName="Order")`, потому что Jackson XML
регистрозависим (XML-элементы в IIB традиционно начинаются с большой буквы).

---

## Шаг 7. Запуск runtime-сервиса

Boot-jar стартует автономно: встроенный Artemis поднимается вместе с сервисом:

```bash
java -jar runtime/target/iib-camel-runtime-0.0.1-SNAPSHOT.jar
```

Ожидаемый лог:

```text
Routes startup (total:1)
    Started Adapter (jms://ADAPTER.IN)
Apache Camel 4.22.0 (camel-1) started ... (build/init:0ms start:…)
Started IibCamelApplication in … seconds
```

Ctrl+C останавливает сервис.

> Для отладки из Maven эквивалент: `mvn -pl runtime spring-boot:run`.

**Подключение к внешнему брокеру (FR-R3.x):**

```bash
java -Dspring.artemis.mode=NATIVE \
     -Dspring.artemis.host=mq.example.com \
     -Dspring.artemis.port=61616 \
     -Dspring.artemis.user=<***> \
     -Dspring.artemis.password=<***> \
     -jar runtime/target/iib-camel-runtime-0.0.1-SNAPSHOT.jar
```

Пара важных настроек JMS-компонента встроена в код/конфигурацию:

| Настройка | Где | Аналог в IIB |
|---|---|---|
| `camel.component.jms.preserve-message-qos=true` | `application.properties` | копирование `MQMD.Priority` |
| `jmsMessageType=Text` + XML-renderer в `toString()` по модели | `runtime/config/CamelMessagingConfig.java`, `runtime/model/Adapter.java` | перенос `OutputRoot` как XML-сообщения |

---

## Шаг 8. Сквозная проверка миграции (e2e, уровень L3)

Тестовые сценарии для потока хранятся в декларативном датасете `dataset.yml`
(структура в `runtime/src/test/resources/e2e/corr-01-simple-adapter/`).
Проверка корректности маршрута на реальном исполнении (встроенный Artemis):

```bash
mvn -pl runtime test -Dtest=E2eDatasetTest
```

Ожидаемо: `Tests run: 1, Failures: 0` — датасет содержит три кейса:
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
2. [ ] `diagram` → топология визуально соответствует ожиданиям (Шаг 3).
3. [ ] `convert` → `converted/<Имя>-route.xml` + отчёт (Шаг 4).
4. [ ] В отчёте «не поддерживается: 0» либо за mark вручную сформулирован план
       доработки unsupported-узлов (FR-2.7) (Шаг 5).
5. [ ] Маршрут скопирован в `runtime/src/main/resources/camel/` (Шаг 6).
6. [ ] Для каждого Compute/бboss-узла реализован `@Component`-бин с camelcase-имя
       модуля (Шаг 6.1) и POJO-модель (Шаг 6.2).
7. [ ] Датасет e2e заполнен сценариями (happy path + работоспособность приоритетов
       + некорректное сообщение) и тестирован (Шаг 8).
8. [ ] Проверено поведение с внешним брокером, если в цели — не с embedded (Шаг 7).

---

## Приложение А. Коды возврата CLI и диагностика

Обе подкоманды (`diagram`, `convert`) возвращают:

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
  правила генерации в `converter/src/main/java/com/example/iib_camel/converter/camel/RouteGenerator.java`
  (ветка `walk`/`emitChoice`) — тесты на golden-файлы помогут;
* по мере добавления новых правил обновляйте golden-файл `expected/<Имя>-route.xml`
  (первое утверждение — фикстур, см. `docs/test-strategy.ru.md`, 3.2);
* отчёт FR-5.1 при этом становится историей миграции: keep до закрытия проекта
  перешеcтрования всех потоков.
