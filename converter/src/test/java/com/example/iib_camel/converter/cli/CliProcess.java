package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.ConverterApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class CliProcess {
    static int run(Path workingDirectory, String... arguments) throws Exception {
        var command = new ArrayList<>(Arrays.asList(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ConverterApplication.class.getName()));
        command.addAll(Arrays.asList(arguments));
        Path log = workingDirectory.resolve("cli.log");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("CLI timed out: " + Files.readString(log));
        }
        return process.exitValue();
    }
}
