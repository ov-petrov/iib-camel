package ru.petrovov.iib_camel.runtime_lite.xsd;

import org.xml.sax.SAXException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import javax.lang.model.SourceVersion;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "xsd-tool", mixinStandardHelpOptions = true,
    description = "Generate Jakarta XML Binding DTOs and XSD-validated XML samples.")
public final class XsdTool implements Callable<Integer> {

    private static final String DTO_PACKAGE = "ru.petrovov.iib_camel.runtime_lite.generated";

    @Spec
    private CommandSpec spec;

    @Option(names = "--xsd", description = "Generate from a single entry XSD file.")
    private Path xsd;

    @Option(names = "--input", description = "Batch input directory. Defaults to input next to the JAR.")
    private Path input;

    @Option(names = "--out", description = "Output directory. Defaults to output next to the JAR.")
    private Path output;

    @Option(names = "--mode", defaultValue = "BOTH", description = "Artifact selection: ${COMPLETION-CANDIDATES}.")
    private Mode mode;

    @Option(names = "--package", defaultValue = DTO_PACKAGE, description = "Base Java package for generated DTOs.")
    private String packageName;

    @Option(names = "--root", description = "Sample root QName for --xsd. Repeat for multiple samples.")
    private List<String> rootNames;

    public static void main(String[] args) {
        int result = commandLine().execute(args);
        if (result != 0) {
            System.exit(result);
        }
    }

    static CommandLine commandLine() {
        return new CommandLine(new XsdTool()).setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionExceptionHandler((failure, command, result) -> {
                command.getErr().println(failure.getMessage());
                return command.getCommandSpec().exitCodeOnExecutionException();
            });
    }

    @Override
    public Integer call() throws Exception {
        if (mode != Mode.XML && !SourceVersion.isName(packageName, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException("Invalid Java package: " + packageName);
        }
        if (mode == Mode.DTO && rootNames != null) {
            throw new IllegalArgumentException("--root applies to XML samples");
        }
        if (input != null && xsd != null) {
            throw new IllegalArgumentException("Choose either --input or --xsd");
        }
        if (xsd == null && rootNames != null) {
            throw new IllegalArgumentException("--root requires --xsd");
        }
        Path home = toolDirectory();
        Path destination = (output == null ? home.resolve("output") : output).toAbsolutePath().normalize();
        if (xsd == null) {
            return generateBatch(input == null ? home.resolve("input") : input, destination);
        }
        generate(xsd, packageName, destination, false);
        return 0;
    }

    private Path toolDirectory() throws Exception {
        Path location = Path.of(XsdTool.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.isRegularFile(location) ? location.toAbsolutePath().getParent() : Path.of("").toAbsolutePath();
    }

    private int generateBatch(Path directory, Path destination) throws Exception {
        var schemas = SchemaInputs.entrySchemas(directory);
        Files.createDirectories(destination);
        var packageNames = new HashSet<String>();
        int result = 0;
        for (Path schema : schemas) {
            String name = schema.getFileName().toString();
            String stem = name.substring(0, name.length() - ".xsd".length());
            try {
                if (stem.isBlank() || stem.equals(".") || stem.equals("..")) {
                    throw new IllegalArgumentException("XSD file name must have a usable base name");
                }
                String segment = stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
                if (!SourceVersion.isName(segment, SourceVersion.RELEASE_21)) {
                    segment = "schema_" + segment;
                }
                String dtoPackage = packageName + "." + uniqueName(segment, packageNames);
                generate(schema, dtoPackage, destination.resolve(stem), true);
            } catch (Exception failure) {
                spec.commandLine().getErr().println(name + ": " + failure.getMessage());
                result = 1;
            }
        }
        return result;
    }

    private void generate(Path schema, String dtoPackage, Path destination, boolean batch) throws Exception {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Output directory already exists: " + destination);
        }
        var inputs = SchemaInputs.load(schema);
        Path parent = Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(parent, ".xsd-tool-");
        try {
            if (mode != Mode.XML) {
                new DtoGenerator().generate(inputs, dtoPackage, staging.resolve("dto"), spec.commandLine().getErr());
            }
            if (mode != Mode.DTO) {
                var generator = new XmlSampleGenerator(inputs);
                Collection<QName> roots = batch ? generator.roots() : sampleRoots(inputs);
                if (!roots.isEmpty()) {
                    generateSamples(inputs, generator, roots, Files.createDirectory(staging.resolve("xml")), batch);
                } else if (mode == Mode.XML) {
                    throw new IllegalArgumentException("No concrete global elements for XML samples");
                } else {
                    spec.commandLine().getOut().println("No concrete global elements for XML samples in " + schema);
                }
            }
            Files.move(staging, destination);
        } finally {
            if (Files.exists(staging)) {
                deleteDirectory(staging);
            }
        }
        spec.commandLine().getOut().println("Generated artifacts in " + destination);
    }

    private Set<QName> sampleRoots(SchemaInputs inputs) {
        if (rootNames == null || rootNames.isEmpty()) {
            if (inputs.roots().size() != 1) {
                throw new IllegalArgumentException("Select one or more sample roots with --root. Entry roots: " + inputs.roots());
            }
            return inputs.roots();
        }
        var roots = new LinkedHashSet<QName>();
        var fileNames = new HashSet<String>();
        for (String name : rootNames) {
            QName root = QName.valueOf(name);
            if (!name.startsWith("{")) {
                root = inputs.roots().stream().filter(candidate -> candidate.getLocalPart().equals(name))
                    .findFirst().orElse(root);
            }
            if (roots.add(root) && !fileNames.add(root.getLocalPart())) {
                throw new IllegalArgumentException("Use separate output directories for roots with the same local name: " + root);
            }
        }
        return roots;
    }

    private void generateSamples(SchemaInputs inputs, XmlSampleGenerator generator, Collection<QName> roots,
                                 Path destination, boolean batch) throws Exception {
        var fileNames = new HashSet<String>();
        for (QName root : roots) {
            String candidate = generator.generate(root);
            var validator = inputs.schema().newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try {
                validator.validate(new StreamSource(new StringReader(candidate)));
            } catch (SAXException invalid) {
                throw new IllegalArgumentException("No schema-valid sample was generated for " + root + ": "
                    + invalid.getMessage());
            }
            String localName = root.getLocalPart();
            String fileName = batch ? uniqueName(localName, fileNames) : localName;
            Files.writeString(destination.resolve(fileName + ".xml"), candidate, StandardOpenOption.CREATE_NEW);
        }
    }

    private String uniqueName(String base, Set<String> names) {
        String name = base;
        for (int suffix = 2; !names.add(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = base + "_" + suffix;
        }
        return name;
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    enum Mode {
        BOTH,
        DTO,
        XML
    }
}
