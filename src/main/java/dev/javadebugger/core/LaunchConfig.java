package dev.javadebugger.core;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record LaunchConfig(String mainClass, String classpath, List<String> programArgs) {
    public LaunchConfig {
        mainClass = requireText(mainClass, "mainClass");
        classpath = requireText(classpath, "classpath");
        programArgs = List.copyOf(programArgs == null ? List.of() : programArgs);
    }

    public String commandLine() {
        return Stream.concat(Stream.of(mainClass), programArgs.stream())
                .map(LaunchConfig::escapeArgument)
                .collect(Collectors.joining(" "));
    }

    private static String requireText(String value, String label) {
        String trimmed = Objects.requireNonNull(value, label).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return trimmed;
    }

    private static String escapeArgument(String value) {
        if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return value;
    }
}
