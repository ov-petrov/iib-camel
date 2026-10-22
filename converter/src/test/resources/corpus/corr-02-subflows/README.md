# corr-02-subflows

Fixture категории **L2 (Should)** — сценарий «подпоток + неподдерживаемый узел»: ссылка
на `.subflow`, разворачивание в маршрут и честные статусы отчёта (FR-2.7, FR-5.1).

## Сценарий

```
Order.msgflow
MQInput(ORDERS.IN) ──out──▶ CheckRef [Check.subflow] ──out──▶ MQOutput(ORDERS.OUT)
                   ──catch──────────────────────────────────▶ MQOutput(ORDERS.ERROR.Q)

Check.subflow (внутри, ввод/вывод — явные узлы Input/Output)
Input(eflow:FCMSource) → Compute «Cross-check order» → Trace «Audit trace» → Output(eflow:FCMSink)
                       (Check_Compute1.esql)              (не поддерживается, FR-2.7)
```

- подпоток `Check.subflow` вызывается из родительского потока узлом-ссылкой
  `xmi:type="Check.subflow:FCMComposite_1"` (формат по канону `ot4i/ace-bob`,
  `skills/shared/node-types.md`: узел-ссылка — `<Имя>.subflow:FCMComposite_1`,
  ввод/вывод подпотока — `eflow:FCMSource` / `eflow:FCMSink`);
- внутри подпотока Trace-узел намеренно оставлен: конвертер честно помечает его
  «не поддерживается» и создаёт TODO-заглушку (FR-2.7) — это testing-кейс для отчёта;
- ESQL корректно содержится в `Check_Compute1.esql` и **переводится автоматически** (FR-3.2):
  корень + MQMD-метка — бин сгенерирован, статус «конвертировано».

## Ожидаемый результат конвертации

`expected/Order-route.xml` — один файл с двумя маршрутами: родительский `Order`
(вызов подпотока через `<to uri="direct:Check"/>`) и вложенный `Check`
(`<from uri="direct:Check"/>`, развёрнутое содержимое подпотока с заглушкой Trace).
Статусы в отчёте `migration-report.md`:

| Единица | Статус |
|---|---|
| Input (MQInput) | конвертировано |
| CheckRef (Subflow) | конвертировано (direct:Check) |
| Acked, Rejected (MQOutput) | конвертировано |
| SourceFlow (Input), SinkFlow (Output) | конвертировано |
| CheckStep (Compute) | конвертировано → bean:checkCompute1 |
| TraceNode (Trace) | **не поддерживается** (TODO-заглушка, FR-2.7) |
| Check_Compute1 (ESQL) | конвертировано — бин `beans/CheckCompute1.java` (FR-3.2) |

## Формат файлов

Верифицирован по канону типов узлов ACE (`ot4i/ace-bob`, `skills/shared/node-types.md`):
формат `.msgflow`/`.subflow` — Eclipse EMF/XMI (`ecore:EPackage`, namespace
`http://www.ibm.com/wbi/2005/eflow`), узлы `ComIbm*.msgnode:FCMComposite_1`,
связи `eflow:FCMConnection`; узел-ссылка на подпоток несёт xmlns-декларацию префикса.
Ввод/вывод `.subflow` — `eflow:FCMSource`/`eflow:FCMSink` (каждый Input-узел подпотока —
терминал `In` узла-ссылки, каждый Output-узел — терминал `Out`; IBM Docs,
«Creating a subflow»). ESQL — стандартный (`BROKER SCHEMA`, `CREATE COMPUTE MODULE`).

## Статус golden

`expected/Order-route.xml` создаётся на первом прогоне конвертера и утверждается
вручную (см. стратегию тестирования, раздел 3.2). Добавился и уровень L3: сгенерированный
бин `CheckCompute1` и маршрут `Order-route.xml` выполняются на встроенном Artemis в runtime
(`runtime/src/test/resources/e2e/corr-02-subflows/`), см. `E2eDatasetTest` — проверяются
перенос тела, приоритет и JMS-свойство CommentField.
