# Инструкция по использованию xsd-tool

Требуются JDK 21 и Maven 3.9+. Команды сборки ниже выполняются в Windows PowerShell из корня репозитория.

## 1. Собрать инструмент

```powershell
mvn -B -f .\runtime-lite\xsd-tool\pom.xml clean package
```

Готовый исполняемый файл находится в `runtime-lite\xsd-tool\target\xsd-tool.jar`. Скопируйте его в рабочий каталог
и создайте рядом `input` и `output`.

```powershell
New-Item -ItemType Directory -Force -Path .\.private\xsd-work\input, .\.private\xsd-work\output
Copy-Item .\runtime-lite\xsd-tool\target\xsd-tool.jar .\.private\xsd-work\xsd-tool.jar
```

Поместите входные XSD непосредственно в `input`. Зависимые схемы можно расположить во вложенных каталогах и подключать
относительными путями через `include`, `import` или `redefine`. Ссылки должны оставаться в пределах `input`.
Пробелы в `schemaLocation` кодируйте как `%20`. Для пробного запуска можно скопировать две синтетические схемы.

```powershell
Copy-Item .\runtime-lite\src\test\resources\xml\xsd-tool\payment.xsd .\.private\xsd-work\input\
Copy-Item .\runtime-lite\src\test\resources\xml\service\sample.xsd .\.private\xsd-work\input\
```

## 2. Запустить пакетную генерацию

```powershell
java -jar .\.private\xsd-work\xsd-tool.jar
```

Без аргументов инструмент ищет `input` рядом с JAR и создаёт там же `output`, независимо от текущего каталога PowerShell. Для каждого входного графа результаты размещаются по имени входной схемы без расширения:

```text
output/
  <имя-схемы>/
    dto/
    xml/
```

В `dto` находятся Java-исходники. В `xml` создаётся пример для каждого конкретного глобального элемента входной схемы
и её зависимостей. Базовый пакет DTO `ru.petrovov.iib_camel.runtime_lite.generated` дополняется уникальным суффиксом схемы.

Общий каталог `output` может уже существовать. Существующий каталог результата конкретной схемы защищён от перезаписи: инструмент выдаёт диагностику. Для повторного запуска выберите другой выходной каталог или предварительно переместите нужный результат.

Пути и вид результатов можно задать явно:

```powershell
java -jar .\.private\xsd-work\xsd-tool.jar --input .\.private\xsd-work\input --out .\.private\xsd-work\output-new --mode both --package ru.petrovov.iib_camel.runtime_lite.generated
```

| Параметр | Назначение |
| --- | --- |
| `--input` | Каталог входных схем. По умолчанию `input` рядом с JAR. |
| `--out` | Каталог результатов. По умолчанию `output` рядом с JAR. |
| `--mode` | `both` генерирует DTO и XML. `dto` или `xml` выбирает один вид. По умолчанию `both`. |
| `--package` | Базовый Java-пакет для DTO. По умолчанию `ru.petrovov.iib_camel.runtime_lite.generated`. |

## 3. Обработать одну схему

Для явного выбора отдельной XSD используйте `--xsd` и новый каталог `--out`. Например, со схемой из синтетического примера репозитория:

```powershell
java -jar .\runtime-lite\xsd-tool\target\xsd-tool.jar `
  --xsd .\runtime-lite\src\test\resources\xml\xsd-tool\payment.xsd `
  --out .\.private\xsd-single `
  --package ru.petrovov.iib_camel.runtime_lite.generated.example
```

В этом режиме `dto` и `xml` создаются непосредственно в `--out`, а сам каталог `--out` должен быть новым. Зависимости схемы указываются относительными путями в пределах каталога входной XSD.

Параметр `--root` применяется вместе с `--xsd` и может повторяться для нескольких корней. Без него инструмент
выбирает единственный глобальный элемент входной XSD. Если их несколько, укажите нужные. Для элемента из подключённой
схемы используйте точный QName, например `--root '{urn:sample}Envelope'`. Для корня без пространства имён укажите
локальное имя.

Если выбранный XML не проходит валидацию, команда выводит причину. Чтобы получить только Java-исходники, используйте `--mode dto` без параметров `--root`.

## 4. Скопировать DTO в нужный модуль

Скопируйте содержимое `output/<имя-схемы>/dto` или `<каталог --out>/dto` для одиночного запуска в каталог
Java-исходников нужного модуля, сохраняя структуру пакетов. Команды ниже копируют DTO из синтетической схемы,
сгенерированные на шаге 3, в `runtime-lite/src/main/java`. Для проверки документа из кода ниже скопируйте также
XSD в `src/main/resources/schemas/example/payment.xsd` целевого модуля. Maven включит схему в classpath приложения.

```powershell
Copy-Item -Path .\.private\xsd-single\dto\* -Destination .\runtime-lite\src\main\java -Recurse
New-Item -ItemType Directory -Force -Path .\runtime-lite\src\main\resources\schemas\example
Copy-Item .\runtime-lite\src\test\resources\xml\xsd-tool\payment.xsd .\runtime-lite\src\main\resources\schemas\example\
mvn -B -f .\runtime-lite\pom.xml clean package
```

В `runtime-lite` внедрите Spring-бин `XmlService` для чтения и записи JAXB DTO. Например, сгенерированный
на шаге 3 класс можно прочитать и записать с явной проверкой схемы.

```java
String schema = "classpath:schemas/example/payment.xsd";
var document = xmlService.readValidated(
    input,
    schema,
    ru.petrovov.iib_camel.runtime_lite.generated.example.PaymentDocument.class
);
var output = xmlService.writeValidatedDocument(document, schema);
```

Для чтения `XmlService` учитывает `ObjectFactory` из пакета DTO. Если XJC сгенерировал для корневого элемента
метод `ObjectFactory.create…(dto)`, при записи передайте полученный `JAXBElement` в `write()` или
`writeValidatedDocument()`.

Для сборки DTO в другом модуле проверьте наличие `jakarta.xml.bind-api` и реализации, например `jaxb-runtime`.
Создавайте `JAXBContext` по классу DTO или `ObjectFactory` из соответствующего пакета. Конфиденциальные XSD
и полученные из них файлы храните в `.private/` или вне репозитория.
