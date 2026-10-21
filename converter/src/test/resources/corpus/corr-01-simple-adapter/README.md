# corr-01-simple-adapter

Fixture категории **L1 (Must)** — сценарий «несложный адаптер»: модификация сообщения + маршрутизация.

## Сценарий

```
MQInput(ADAPTER.IN) ──out──▶ Compute «Stamp MQMD» ──out──▶ Route ($Body/Adapter/Order/status）
                            (Adapter_Compute1.esql)        ├── Match   ──▶ MQOutput(ADAPTER.PRIORITY.Q)
                                                           └── Default ──▶ MQOutput(ADAPTER.DEFAULT.Q)
                    ──catch────────────────────────────────────────────▶ MQOutput(ADAPTER.ERROR.Q)
```

- Compute копирует `InputRoot` в `OutputRoot` и ставит метку `MQMD.ApplicationIdData = 'IIB-CAMEL-MIG'`;
- Route расходит по терминалу `Match` (status='HIGH') и `Default`;
- malformed-сообщение (не разбирается как XML/XMLNSC) уходит в catch → `ADAPTER.ERROR.Q`.

## Формат файлов

Верифицирован по реальным примерам из официального репозитория IBM (org `ot4i`):
`file-http-pattern`, `event-filter-pattern`, `service-access-mq-oneway` — формат `.msgflow`
это Eclipse EMF/XMI (`ecore:EPackage`, namespace `http://www.ibm.com/wbi/2005/eflow`), узлы
`ComIbm*.msgnode:FCMComposite_1` со свойствами-атрибутами, связи — `eflow:FCMConnection`,
Route — `filterTable`/`filterPattern`, Compute — `computeExpression="esql://routine/<schema>#<module>.Main"`.
ESQL — стандартный (`BROKER SCHEMA`, `CREATE COMPUTE MODULE`).

## Статус golden

`expected/` пока содержит только e2e-набор. Golden-файл маршрута (`Adapter.route.xml`)
создаётся на первом прогоне конвертера и утверждается вручную (см. стратегию тестирования,
раздел 3.2: «first appearance — generation without golden»).
