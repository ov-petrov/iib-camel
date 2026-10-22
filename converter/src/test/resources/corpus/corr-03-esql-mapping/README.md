# corr-03-esql-mapping — частичный перевод ESQL-модуля

## Что здесь

Минимальный IIB-поток **Adjust**: `MQInput(ADJUST.IN)` → `Compute(Adjust_Compute1)` → `MQOutput(ADJUST.OUT)`,
обработчик `catch` → `ADJUST.ERROR.Q` (аналог corr-01, без Route-узла).

`Adjust_Compute1.esql` — модуль, который **переводится частично** (FR-3.2 + FR-3.3):

- `SET OutputRoot = InputRoot;` — переводится (`esql.copy`);
- `SET OutputRoot.MQMD.CommentField = 'ADJUSTED';` — переводится (JMS-свойство);
- `SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED';` — переводится (правка XML-дерева);
- `SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL);` — переводится (литерал + примечание о типе);
- `IF InputBody.Order.Status = 'HOLD' THEN … END IF;` — **не переводится**: честный статус
  «частично», в бине — TODO-заглушка с текстом всего ветвления (внутренний SET виден там же
  и не превращается в безусловный оператор — ничего не теряется молча, FR-5.1).

## Как проверить

```bash
java -jar converter.jar convert --input converter/src/test/resources/corpus/corr-03-esql-mapping --output /tmp/adj
```

Ожидается:

- `/tmp/adj/Adjust-route.xml` — побайтово равен `expected/Adjust-route.xml` (golden);
- `/tmp/adj/beans/AdjustCompute1.java` — побайтово равен `expected/AdjustCompute1.java`,
  4 переведённых оператора + `// TODO (FR-3.3): IF …`;
- `migration-report.md`: модуль `Adjust_Compute1` — статус **«частично»**, таблица «Операторы ESQL»
  — 4 × «конвертировано», полнота автоматизации **80.0%** (4 из 5 единиц конвертировано).

## Статус e2e

Потенциал тот же, что у corr-01 (MQInput → Compute → MQOutput), но e2e-прогон в runtime
не настроен намеренно: покрывается corr-01 (ручной бин) и corr-02 (сгенерированный бин).
После ручной доработки TODO в бине корректировка ветвления станет автоматической —
тогда e2e corr-03 добавится без изменения конвертера.
