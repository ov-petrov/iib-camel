package ru.petrovov.iib_camel.converter.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class ArtifactOutput {
    private final Path root;
    private final String manifestName;
    private final Map<String, String> artifacts = new LinkedHashMap<>();

    ArtifactOutput(Path root, String mode) {
        this.root = root.toAbsolutePath().normalize();
        this.manifestName = ".iib-camel-" + mode + ".tsv";
    }

    void stage(String name, String content) {
        resolve(name);
        String existing = artifacts.putIfAbsent(name, content);
        if (existing != null && !existing.equals(content)) {
            throw new IllegalArgumentException("конфликт выходного артефакта: " + name);
        }
    }

    static String fileStem(String name) {
        if (name.isBlank() || !name.matches("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]*") || name.contains("..")) {
            throw new IllegalArgumentException("небезопасное имя артефакта: " + name);
        }
        return name.replace('.', '-');
    }

    void publish() throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("каталог вывода не должен быть символической ссылкой: " + root);
        }
        Files.createDirectories(root);
        Map<String, String> previous = readManifest();
        for (var artifact : artifacts.entrySet()) {
            Path target = resolve(artifact.getKey());
            rejectSymlinks(target);
            checkOwnedOrIdentical(target, previous.get(artifact.getKey()), artifact.getValue());
        }
        for (var old : previous.entrySet()) {
            if (!artifacts.containsKey(old.getKey())) {
                Path stale = resolve(old.getKey());
                rejectSymlinks(stale);
                if (Files.exists(stale) && !hash(Files.readString(stale)).equals(old.getValue())) {
                    throw new IOException("устаревший generated-файл изменён вручную: " + stale);
                }
            }
        }
        for (var artifact : artifacts.entrySet()) {
            atomicWrite(resolve(artifact.getKey()), artifact.getValue());
        }
        for (String old : previous.keySet()) {
            if (!artifacts.containsKey(old)) Files.deleteIfExists(resolve(old));
        }
        var manifest = new StringBuilder();
        artifacts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                manifest.append(e.getKey()).append('\t').append(hash(e.getValue())).append('\n'));
        atomicWrite(resolve(manifestName), manifest.toString());
    }

    private Map<String, String> readManifest() throws IOException {
        Path path = resolve(manifestName);
        rejectSymlinks(path);
        var result = new LinkedHashMap<String, String>();
        if (!Files.exists(path)) return result;
        for (String line : Files.readAllLines(path)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || !fields[1].matches("[0-9a-f]{64}") || fields[0].equals(manifestName)) {
                throw new IOException("повреждён manifest: " + path);
            }
            resolve(fields[0]);
            if (result.putIfAbsent(fields[0], fields[1]) != null) throw new IOException("дубликат в manifest: " + fields[0]);
        }
        return result;
    }

    private Path resolve(String name) {
        Path relative = Path.of(name);
        Path result = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !result.startsWith(root) || result.equals(root)
                || name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("путь вне каталога вывода: " + name);
        }
        return result;
    }

    private void rejectSymlinks(Path target) throws IOException {
        for (Path path = target; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("символическая ссылка в пути вывода: " + path);
        }
    }

    private static void checkOwnedOrIdentical(Path target, String previousHash, String content) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        String current = Files.readString(target);
        if (!current.equals(content) && (previousHash == null || !hash(current).equals(previousHash))) {
            throw new IOException("файл существует и не принадлежит текущей генерации либо изменён вручную: " + target);
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".iib-camel-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
