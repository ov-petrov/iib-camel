package ru.petrovov.iib_camel.converter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArtifactOutputTest {
    @TempDir
    Path tempDir;

    @Test
    void refusesParentAndAbsoluteOutputPaths() {
        var output = new ArtifactOutput(tempDir.resolve("out"), "convert");

        assertThrows(IllegalArgumentException.class, () -> output.stage("../escape.txt", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> output.stage(tempDir.resolve("absolute.txt").toString(), "x"));
    }

    @Test
    void refusesSymlinkWithinOutputRoot() throws Exception {
        Path root = tempDir.resolve("out");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = root.resolve("linked");
        Files.createDirectories(root);
        createSymlinkOrSkip(link, outside);
        var output = new ArtifactOutput(root, "convert");
        output.stage("linked/artifact.txt", "generated");

        assertThrows(IOException.class, output::publish);
        assertFalse(Files.exists(outside.resolve("artifact.txt")));
    }

    @Test
    void refusesOutputRootThatIsASymlink() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path root = tempDir.resolve("out-link");
        createSymlinkOrSkip(root, target);
        var output = new ArtifactOutput(root, "convert");
        output.stage("artifact.txt", "generated");

        assertThrows(IOException.class, output::publish);
        assertFalse(Files.exists(target.resolve("artifact.txt")));
    }

    @Test
    void refusesToOverwriteUnknownExistingFile() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("out"));
        Path file = root.resolve("artifact.txt");
        Files.writeString(file, "user content");
        var output = new ArtifactOutput(root, "convert");
        output.stage("artifact.txt", "generated");

        assertThrows(IOException.class, output::publish);
        assertEquals("user content", Files.readString(file));
    }

    @Test
    void refusesToReplaceManuallyModifiedOwnedFile() throws Exception {
        Path root = tempDir.resolve("out");
        publish(root, "convert", "artifact.txt", "generated v1");
        Path file = root.resolve("artifact.txt");
        Files.writeString(file, "manual edit");
        var output = new ArtifactOutput(root, "convert");
        output.stage("artifact.txt", "generated v2");

        assertThrows(IOException.class, output::publish);
        assertEquals("manual edit", Files.readString(file));
    }

    @Test
    void replacesUnmodifiedOwnedFileOnRerun() throws Exception {
        Path root = tempDir.resolve("out");
        publish(root, "convert", "artifact.txt", "generated v1");
        publish(root, "convert", "artifact.txt", "generated v2");

        assertEquals("generated v2", Files.readString(root.resolve("artifact.txt")));
    }

    @Test
    void removesOnlyStaleUnchangedOwnedArtifacts() throws Exception {
        Path root = tempDir.resolve("out");
        var first = new ArtifactOutput(root, "convert");
        first.stage("keep.txt", "old keep");
        first.stage("stale.txt", "old stale");
        first.publish();
        Files.writeString(root.resolve("unrelated.txt"), "user file");
        var rerun = new ArtifactOutput(root, "convert");
        rerun.stage("keep.txt", "new keep");
        rerun.publish();

        assertEquals("new keep", Files.readString(root.resolve("keep.txt")));
        assertFalse(Files.exists(root.resolve("stale.txt")));
        assertEquals("user file", Files.readString(root.resolve("unrelated.txt")));
    }

    @Test
    void modifiedStaleArtifactBlocksPublishBeforeCurrentFilesChange() throws Exception {
        Path root = tempDir.resolve("out");
        var first = new ArtifactOutput(root, "convert");
        first.stage("current.txt", "current v1");
        first.stage("stale.txt", "stale v1");
        first.publish();
        Files.writeString(root.resolve("stale.txt"), "manual stale edit");
        var rerun = new ArtifactOutput(root, "convert");
        rerun.stage("current.txt", "current v2");

        assertThrows(IOException.class, rerun::publish);
        assertEquals("current v1", Files.readString(root.resolve("current.txt")));
        assertEquals("manual stale edit", Files.readString(root.resolve("stale.txt")));
    }

    @Test
    void conflictingContentForSameStagedPathIsRejected() {
        var output = new ArtifactOutput(tempDir.resolve("out"), "convert");
        output.stage("artifact.txt", "first");

        assertThrows(IllegalArgumentException.class, () -> output.stage("artifact.txt", "second"));
    }

    private static void publish(Path root, String mode, String name, String content) throws IOException {
        var output = new ArtifactOutput(root, mode);
        output.stage(name, content);
        output.publish();
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }
    }
}
