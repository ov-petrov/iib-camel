package com.example.iib_camel.converter.esql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор модулей ESQL (FR-3.1). Уровень MVP: распознаются `BROKER SCHEMA`,
 * `CREATE {COMPUTE|DATABASE|FILTER} MODULE`, функции без параметров, присваивания
 * `SET <target> = <value>;` и `RETURN TRUE;`. Более сложные конструкции помечаются
 * в отчёте как требующие ручной доработки (FR-3.3).
 */
public final class EsqlParser {

    private static final Pattern BROKER_SCHEMA =
            Pattern.compile("BROKER\\s+SCHEMA\\s+([\\w./]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE = Pattern.compile(
            "CREATE\\s+(COMPUTE|DATABASE|FILTER)\\s+MODULE\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUNCTION = Pattern.compile(
            "CREATE\\s+FUNCTION\\s+(\\w+)\\s*\\(\\s*\\)\\s+RETURNS\\s+(\\w+)\\s+BEGIN",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SET = Pattern.compile(
            "\\bSET\\s+([^=;]+?)\\s*=\\s*(.+?);", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_TRUE =
            Pattern.compile("\\bRETURN\\s+TRUE\\s*;", Pattern.CASE_INSENSITIVE);
    /** Конструкции вне переводимого подмножества (FR-3.2 base): Statements с ветвлениями и вызовами. */
    private static final Pattern UNSUPPORTED_STATEMENT = Pattern.compile(
            "\\b(IF|WHILE|CALL|THROW)\\s[^;]*;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final String END_MODULE = "END MODULE;";
    private static final String END_FUNCTION = "END;";

    /** Для типового случая «одна ESQL-схема — один модуль». */
    public EsqlModule parse(String source) {
        List<EsqlModule> modules = parseAll(source);
        if (modules.size() != 1) {
            throw new IllegalArgumentException("ожидался один модуль ESQL, найдено: " + modules.size());
        }
        return modules.getFirst();
    }

    /** Все модули исходника (для отчёта FR-5.1: единицы «модуль ESQL»). */
    public List<EsqlModule> parseAll(String source) {
        String schema = match(BROKER_SCHEMA, source).orElse("");
        List<EsqlModule> modules = new ArrayList<>();
        Matcher moduleMatcher = MODULE.matcher(source);
        while (moduleMatcher.find()) {
            String type = moduleMatcher.group(1).toUpperCase(Locale.ROOT);
            String name = moduleMatcher.group(2);
            int moduleEnd = indexOfIgnoreCase(source, moduleMatcher.end(), END_MODULE);
            if (moduleEnd < 0) {
                throw new IllegalArgumentException(
                        "модуль \"" + name + "\": не найдено закрывающее \"" + END_MODULE + "\"");
            }
            modules.add(new EsqlModule(schema, name, type,
                    functions(type, source, moduleMatcher.end(), moduleEnd)));
            moduleMatcher.region(moduleEnd, source.length());
        }
        return modules;
    }

    private List<EsqlFunction> functions(String moduleType, String source, int moduleStart, int moduleEnd) {
        List<EsqlFunction> functions = new ArrayList<>();
        Matcher functionMatcher = FUNCTION.matcher(source).region(moduleStart, moduleEnd);
        while (functionMatcher.find()) {
            String name = functionMatcher.group(1);
            int bodyEnd = indexOfIgnoreCase(source, functionMatcher.end(), END_FUNCTION);
            if (bodyEnd < 0) {
                throw new IllegalArgumentException(
                        "функция \"" + name + "\": не найдено закрывающее \"" + END_FUNCTION + "\"");
            }
            functions.add(function(name, moduleType, source, functionMatcher.end(), bodyEnd));
            functionMatcher.region(bodyEnd, moduleEnd);
        }
        return List.copyOf(functions);
    }

    private EsqlFunction function(String name, String moduleType, String source,
                                  int bodyStart, int bodyEnd) {
        String body = source.substring(bodyStart, bodyEnd);
        // каждая конструкция IF/WHILE/CALL/THROW — честная причина «частично» (FR-3.3)
        List<String> unsupported = new ArrayList<>();
        List<int[]> unsupportedRanges = new ArrayList<>();
        Matcher unsupportedStatement = UNSUPPORTED_STATEMENT.matcher(body);
        while (unsupportedStatement.find()) {
            unsupported.add(unsupportedStatement.group().strip().replaceAll("\\s+", " "));
            unsupportedRanges.add(new int[] {unsupportedStatement.start(), unsupportedStatement.end()});
        }
        // SET-операторы внутри IF/WHILE не переводим: их ветвлённый контекст неизвестен,
        // они остаются внутри TODO-фрагмента (ничего не теряется молча, FR-5.1)
        List<EsqlAssignment> plain = new ArrayList<>();
        Matcher setMatcher = SET.matcher(body);
        while (setMatcher.find()) {
            boolean insideBranch = unsupportedRanges.stream()
                    .anyMatch(r -> setMatcher.start() >= r[0] && setMatcher.start() < r[1]);
            if (!insideBranch) {
                plain.add(new EsqlAssignment(setMatcher.group(1).strip(),
                        setMatcher.group(2).strip()));
            }
        }
        // функции кроме Main — вне переводимого подмножества; заметка закрепляется
        // за самой функцией, а на статус модуля она влияет через генератор (FR-3.3)
        if (!"Main".equals(name)) {
            unsupported.add("функция " + name + ": функция кроме Main не переводится автоматически");
        }
        // FILTER-модули требуют булевой логики потока — вне переводимого подмножества
        if ("FILTER".equals(moduleType)) {
            unsupported.add("FILTER-модуль: булев RETURN управляет терминалами потока — "
                    + "перевод требует правила для filter");
        }
        boolean returnsTrue = RETURN_TRUE.matcher(body).find();
        return new EsqlFunction(name, "BOOLEAN", List.copyOf(plain), returnsTrue,
                List.copyOf(unsupported));
    }

    private Optional<String> match(Pattern pattern, String source) {
        Matcher m = pattern.matcher(source);
        return m.find() ? Optional.ofNullable(m.group(1)) : Optional.empty();
    }

    private static int indexOfIgnoreCase(String haystack, int from, String needle) {
        String haystackUpper = haystack.toUpperCase(Locale.ROOT);
        String needleUpper = needle.toUpperCase(Locale.ROOT);
        return haystackUpper.indexOf(needleUpper, from);
    }
}
