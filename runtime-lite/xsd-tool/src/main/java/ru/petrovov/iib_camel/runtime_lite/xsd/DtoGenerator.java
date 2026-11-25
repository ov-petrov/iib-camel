package ru.petrovov.iib_camel.runtime_lite.xsd;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.comments.Comment;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.writer.FileCodeWriter;
import com.sun.tools.xjc.api.ErrorListener;
import com.sun.tools.xjc.api.XJC;
import org.xml.sax.SAXParseException;

import javax.lang.model.SourceVersion;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class DtoGenerator {

    void generate(SchemaInputs inputs, String packageName, Path destination, PrintWriter diagnostics) throws IOException {
        var listener = new XjcDiagnostics(diagnostics);
        var compiler = XJC.createSchemaCompiler();
        compiler.setErrorListener(listener);
        compiler.setEntityResolver(inputs.entityResolver());
        compiler.setDefaultPackageName(packageName);
        compiler.parseSchema(inputs.entrySource());
        var model = compiler.bind();
        if (model == null || listener.hasErrors) {
            throw new IllegalArgumentException("XJC could not bind the schema");
        }
        var code = model.generateCode(null, listener);
        if (listener.hasErrors) {
            throw new IllegalArgumentException("XJC could not generate Java sources");
        }
        Files.createDirectory(destination);
        code.build(new FileCodeWriter(destination.toFile(), false, "UTF-8") {
            @Override
            public OutputStream openBinary(JPackage pkg, String fileName) throws IOException {
                if (!pkg.isUnnamed() && !SourceVersion.isName(pkg.name(), SourceVersion.RELEASE_21)) {
                    throw new IOException("Invalid generated Java package: " + pkg.name());
                }
                Path file = Path.of(fileName);
                if (file.isAbsolute() || !fileName.equals(file.getFileName().toString()) || fileName.equals(".")
                    || fileName.equals("..") || fileName.indexOf('\\') >= 0) {
                    throw new IOException("Invalid generated file name: " + fileName);
                }
                Path directory = destination.resolve(pkg.name().replace('.', File.separatorChar));
                Files.createDirectories(directory);
                return Files.newOutputStream(directory.resolve(fileName), StandardOpenOption.CREATE_NEW);
            }
        });
        removeComments(destination);
    }

    private void removeComments(Path destination) throws IOException {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        try (var files = Files.walk(destination)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                var parsed = parser.parse(file);
                if (!parsed.isSuccessful()) {
                    throw new IOException("Cannot parse generated Java source " + destination.relativize(file));
                }
                var unit = parsed.getResult().orElseThrow();
                unit.getAllContainedComments().forEach(Comment::remove);
                Files.writeString(file, unit.toString());
            }
        }
    }

    private static final class XjcDiagnostics implements ErrorListener {

        private final PrintWriter output;
        private boolean hasErrors;

        private XjcDiagnostics(PrintWriter output) {
            this.output = output;
        }

        @Override
        public void error(SAXParseException error) {
            hasErrors = true;
            report(error);
        }

        @Override
        public void fatalError(SAXParseException error) {
            error(error);
        }

        @Override
        public void warning(SAXParseException warning) {
            report(warning);
        }

        @Override
        public void info(SAXParseException info) {
            report(info);
        }

        private void report(SAXParseException diagnostic) {
            output.println("XJC line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage());
        }
    }
}
