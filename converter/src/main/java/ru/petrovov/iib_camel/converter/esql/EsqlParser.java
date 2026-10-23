package ru.petrovov.iib_camel.converter.esql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EsqlParser {

    private static final Set<String> BLOCKS = Set.of("BEGIN", "IF", "CASE", "WHILE", "REPEAT", "LOOP", "FOR");
    private static final Set<String> SIMPLE_UNSUPPORTED = Set.of("CALL", "THROW", "PROPAGATE");

    public EsqlModule parse(String source) {
        List<EsqlModule> modules = parseAll(source);
        if (modules.size() != 1) {
            throw new IllegalArgumentException("ожидался один модуль ESQL, найдено: " + modules.size());
        }
        return modules.getFirst();
    }

    public List<EsqlModule> parseAll(String source) {
        Lexed input = lex(source);
        List<Token> tokens = input.tokens();
        List<EsqlModule> modules = new ArrayList<>();
        String schema = "";
        int i = 0;
        while (i < tokens.size()) {
            if (matches(tokens, i, "BROKER", "SCHEMA")) {
                int start = i + 2;
                i = start;
                while (i < tokens.size() && !tokens.get(i).is(";") && !tokens.get(i).is("CREATE")) {
                    i++;
                }
                if (i == start) {
                    throw error("не указана BROKER SCHEMA", tokens.get(start - 1));
                }
                schema = input.clean().substring(tokens.get(start).start(), tokens.get(i - 1).end()).strip();
                if (!schema.matches("[\\w./]+")) {
                    throw error("неподдерживаемое имя BROKER SCHEMA", tokens.get(start));
                }
                if (i < tokens.size() && tokens.get(i).is(";")) {
                    i++;
                }
                continue;
            }
            if (!matches(tokens, i, "CREATE") || i + 3 >= tokens.size()
                    || !Set.of("COMPUTE", "DATABASE", "FILTER").contains(tokens.get(i + 1).upper())
                    || !tokens.get(i + 2).is("MODULE") || !tokens.get(i + 3).word()) {
                throw error("ожидался CREATE COMPUTE/DATABASE/FILTER MODULE", tokens.get(i));
            }
            String type = tokens.get(i + 1).upper();
            String name = tokens.get(i + 3).text();
            i += 4;
            int moduleEnd = i;
            while (moduleEnd < tokens.size() && !matches(tokens, moduleEnd, "END", "MODULE", ";")) {
                moduleEnd++;
            }
            if (moduleEnd == tokens.size()) {
                throw new IllegalArgumentException("модуль \"" + name + "\": не найдено закрывающее \"END MODULE;\"");
            }
            List<EsqlFunction> functions = new ArrayList<>();
            List<String> moduleIssues = new ArrayList<>();
            while (i < moduleEnd) {
                if (!matches(tokens, i, "CREATE", "FUNCTION")) {
                    int start = i++;
                    while (i < moduleEnd && !matches(tokens, i, "CREATE", "FUNCTION")) {
                        i++;
                    }
                    moduleIssues.add(fragment(source, tokens, start, i));
                    continue;
                }
                int headerStart = i;
                i += 2;
                if (i >= moduleEnd || !tokens.get(i).word()) {
                    throw error("ожидалось имя функции", tokens.get(headerStart));
                }
                String functionName = tokens.get(i++).text();
                int signatureStart = i;
                while (i < moduleEnd && !tokens.get(i).is("BEGIN")) {
                    i++;
                }
                if (i == moduleEnd) {
                    throw error("функция \"" + functionName + "\": не найден BEGIN", tokens.get(headerStart));
                }
                int begin = i;
                int end = blockEnd(tokens, begin, moduleEnd);
                boolean supportedSignature = matches(tokens, signatureStart, "(", ")", "RETURNS", "BOOLEAN")
                        && signatureStart + 4 == begin;
                String returnType = "";
                for (int h = signatureStart; h + 1 < begin; h++) {
                    if (tokens.get(h).is("RETURNS")) {
                        returnType = tokens.get(h + 1).upper();
                        break;
                    }
                }
                EsqlFunction function = function(functionName, returnType, type, input, source, begin + 1, end - 1);
                if (!supportedSignature) {
                    List<String> issues = new ArrayList<>(function.unsupportedFragments());
                    issues.add("неподдерживаемая сигнатура: " + fragment(source, tokens, headerStart, begin));
                    function = new EsqlFunction(function.name(), function.returnType(), function.assignments(),
                            function.returnsTrue(), List.copyOf(issues), function.statements());
                }
                functions.add(function);
                i = end + 1;
            }
            if (!moduleIssues.isEmpty()) {
                for (int f = 0; f < functions.size(); f++) {
                    EsqlFunction function = functions.get(f);
                    List<String> issues = new ArrayList<>(function.unsupportedFragments());
                    issues.addAll(moduleIssues);
                    functions.set(f, new EsqlFunction(function.name(), function.returnType(), function.assignments(),
                            function.returnsTrue(), List.copyOf(issues), function.statements()));
                }
            }
            modules.add(new EsqlModule(schema, name, type, List.copyOf(functions)));
            i = moduleEnd + 3;
        }
        if (!tokens.isEmpty() && modules.isEmpty()) {
            throw new IllegalArgumentException("исходник не содержит модулей ESQL");
        }
        return List.copyOf(modules);
    }

    private EsqlFunction function(String name, String returnType, String moduleType, Lexed input,
                                  String source, int start, int end) {
        List<Token> tokens = input.tokens();
        List<EsqlAssignment> assignments = new ArrayList<>();
        List<EsqlStatement> statements = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        boolean returnsTrue = false;
        int i = start;
        while (i < end) {
            int statementStart = i;
            Token token = tokens.get(i);
            if (BLOCKS.contains(token.upper())) {
                i = blockEnd(tokens, i, end) + 1;
                if (token.is("IF")) {
                    try {
                        statements.add(conditional(input, source, statementStart, i));
                    } catch (IllegalArgumentException e) {
                        unsupported.add(fragment(source, tokens, statementStart, i));
                    }
                } else {
                    unsupported.add(fragment(source, tokens, statementStart, i));
                }
                continue;
            }
            if (!token.is("SET") && !token.is("RETURN") && !SIMPLE_UNSUPPORTED.contains(token.upper())) {
                unsupported.add(fragment(source, tokens, i, end));
                break;
            }
            while (i < end && !tokens.get(i).is(";")) {
                i++;
            }
            if (i == end) {
                throw error("оператор без завершающей точки с запятой", token);
            }
            int semicolon = i++;
            if (token.is("SET")) {
                int equals = statementStart + 1;
                while (equals < semicolon && !tokens.get(equals).is("=")) {
                    equals++;
                }
                if (equals == statementStart + 1 || equals + 1 >= semicolon) {
                    unsupported.add(fragment(source, tokens, statementStart, i));
                    continue;
                }
                assignments.add(new EsqlAssignment(
                        input.clean().substring(tokens.get(statementStart + 1).start(), tokens.get(equals).start()).strip(),
                        input.clean().substring(tokens.get(equals + 1).start(), tokens.get(semicolon).start()).strip()));
                statements.add(new EsqlStatement.Assignment(assignments.getLast()));
            } else if (token.is("RETURN") && matches(tokens, statementStart, "RETURN", "TRUE", ";")
                    && statementStart + 3 == end) {
                returnsTrue = true;
            } else {
                unsupported.add(fragment(source, tokens, statementStart, i));
                if (token.is("RETURN") && i < end) {
                    unsupported.add(fragment(source, tokens, i, end));
                    break;
                }
            }
        }
        if (!"Main".equalsIgnoreCase(name)) {
            unsupported.add("функция " + name + ": функция кроме Main не переводится автоматически");
        }
        if ("DATABASE".equals(moduleType)) {
            unsupported.add("DATABASE-модуль: поддерживается только Compute");
        }
        if ("FILTER".equals(moduleType)) {
            unsupported.add("FILTER-модуль: булев RETURN управляет терминалами потока — требуется правило filter");
        }
        return new EsqlFunction(name, returnType, List.copyOf(assignments), returnsTrue, List.copyOf(unsupported), List.copyOf(statements));
    }

    private static EsqlStatement.Conditional conditional(Lexed input, String source, int start, int end) {
        List<Token> tokens = input.tokens();
        int then = start + 1;
        while (then < end && !tokens.get(then).is("THEN")) then++;
        if (then == end) throw error("IF без THEN", tokens.get(start));
        String condition = fragment(input.clean(), tokens, start + 1, then);
        EsqlExpression.condition(condition);
        int cursor = then + 1;
        List<EsqlStatement> whenTrue = new ArrayList<>();
        List<EsqlStatement> whenFalse = new ArrayList<>();
        List<EsqlStatement> branch = whenTrue;
        while (cursor < end - 3) {
            Token token = tokens.get(cursor);
            if (token.is("ELSE")) {
                if (branch == whenFalse) throw error("повторный ELSE", token);
                branch = whenFalse;
                cursor++;
            } else if (token.is("ELSEIF")) {
                if (branch == whenFalse) throw error("ELSEIF после ELSE", token);
                whenFalse.add(conditional(input, source, cursor, end));
                cursor = end - 3;
            } else if (token.is("IF")) {
                int nestedEnd = blockEnd(tokens, cursor, end) + 1;
                branch.add(conditional(input, source, cursor, nestedEnd));
                cursor = nestedEnd;
            } else if (token.is("SET")) {
                int semicolon = cursor + 1;
                while (semicolon < end && !tokens.get(semicolon).is(";")) semicolon++;
                int equals = cursor + 1;
                while (equals < semicolon && !tokens.get(equals).is("=")) equals++;
                if (equals == cursor + 1 || equals + 1 >= semicolon) throw error("некорректный SET", token);
                branch.add(new EsqlStatement.Assignment(new EsqlAssignment(
                        fragment(input.clean(), tokens, cursor + 1, equals),
                        fragment(input.clean(), tokens, equals + 1, semicolon))));
                cursor = semicolon + 1;
            } else {
                throw error("оператор IF вне подмножества", token);
            }
        }
        return new EsqlStatement.Conditional(condition, List.copyOf(whenTrue), List.copyOf(whenFalse),
                fragment(source, tokens, start, end));
    }

    private static int blockEnd(List<Token> tokens, int start, int limit) {
        List<String> stack = new ArrayList<>();
        stack.add(tokens.get(start).upper());
        boolean statementStart = true;
        for (int i = start + 1; i < limit; i++) {
            Token token = tokens.get(i);
            if (token.is("END")) {
                if (i + 1 >= limit) {
                    break;
                }
                String qualifier = tokens.get(i + 1).is(";") ? "BEGIN" : tokens.get(i + 1).upper();
                if (!BLOCKS.contains(qualifier)) {
                    continue;
                }
                if (!stack.getLast().equals(qualifier)) {
                    throw error("несогласованное завершение блока END " + qualifier, token);
                }
                int semicolon = qualifier.equals("BEGIN") ? i + 1 : i + 2;
                if (semicolon >= limit || !tokens.get(semicolon).is(";")) {
                    throw error("после END ожидается точка с запятой", token);
                }
                stack.removeLast();
                if (stack.isEmpty()) {
                    return semicolon;
                }
                i = semicolon;
                statementStart = true;
                continue;
            }
            if ((statementStart || token.is("BEGIN")) && BLOCKS.contains(token.upper())
                    && !tokens.get(i - 1).is(".")) {
                stack.add(token.upper());
            }
            statementStart = token.is(";") || token.is("THEN") || token.is("ELSE")
                    || token.is("DO") || token.is("BEGIN");
        }
        throw error("не найдено закрытие блока " + tokens.get(start).text(), tokens.get(start));
    }

    private static String fragment(String source, List<Token> tokens, int start, int end) {
        return source.substring(tokens.get(start).start(), tokens.get(end - 1).end()).strip();
    }

    private static boolean matches(List<Token> tokens, int start, String... expected) {
        if (start + expected.length > tokens.size()) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!tokens.get(start + i).is(expected[i])) {
                return false;
            }
        }
        return true;
    }

    private record Token(String text, int start, int end, boolean word) {
        String upper() {
            return word ? text.toUpperCase(Locale.ROOT) : text;
        }

        boolean is(String expected) {
            return upper().equals(expected);
        }
    }

    private record Lexed(String clean, List<Token> tokens) {
    }

    private static Lexed lex(String source) {
        char[] clean = source.toCharArray();
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c) || c == '\ufeff') {
                i++;
                continue;
            }
            int start = i;
            if (source.startsWith("--", i) || source.startsWith("/*", i)) {
                boolean block = source.startsWith("/*", i);
                i += 2;
                if (block) {
                    int end = source.indexOf("*/", i);
                    if (end < 0) {
                        throw new IllegalArgumentException("незавершённый комментарий ESQL: позиция " + start);
                    }
                    i = end + 2;
                } else {
                    while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                        i++;
                    }
                }
                for (int p = start; p < i; p++) {
                    if (clean[p] != '\n' && clean[p] != '\r') {
                        clean[p] = ' ';
                    }
                }
                continue;
            }
            boolean word = Character.isLetter(c) || c == '_';
            if (c == '\'' || c == '"') {
                i++;
                boolean closed = false;
                while (i < source.length()) {
                    if (source.charAt(i++) == c) {
                        if (i < source.length() && source.charAt(i) == c) {
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("незавершённый литерал ESQL: позиция " + start);
                }
            } else if (word) {
                do {
                    i++;
                } while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_'));
            } else {
                i++;
            }
            tokens.add(new Token(source.substring(start, i), start, i, word));
        }
        return new Lexed(new String(clean), List.copyOf(tokens));
    }

    private static IllegalArgumentException error(String message, Token token) {
        return new IllegalArgumentException(message + ": позиция " + token.start());
    }
}
