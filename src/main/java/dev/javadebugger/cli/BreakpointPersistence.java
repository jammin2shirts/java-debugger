package dev.javadebugger.cli;

import dev.javadebugger.core.BreakpointSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BreakpointPersistence {
    private static final String VERSION = "v1";

    private final Path storagePath;

    private BreakpointPersistence(Path storagePath) {
        this.storagePath = storagePath;
    }

    static BreakpointPersistence defaultStore() {
        Path home = Path.of(System.getProperty("user.home"));
        return new BreakpointPersistence(home.resolve(".java-debugger").resolve("breakpoints.dbg"));
    }

    Path storagePath() {
        return storagePath;
    }

    List<BreakpointSpec> load() {
        if (!Files.exists(storagePath)) {
            return List.of();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(storagePath);
        } catch (IOException exception) {
            System.err.println("Warning: could not read saved breakpoints: " + exception.getMessage());
            return List.of();
        }

        if (lines.isEmpty()) {
            return List.of();
        }

        if (!VERSION.equals(lines.get(0))) {
            System.err.println("Warning: unsupported breakpoint storage format at " + storagePath);
            return List.of();
        }

        List<BreakpointSpec> loaded = new ArrayList<>();
        long id = 1;
        Map<String, BreakpointSpec> dedupe = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\t", -1);
            if (parts.length != 3) {
                continue;
            }

            String sourcePath = unescape(parts[0]);
            int lineNumber;
            try {
                lineNumber = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                continue;
            }
            boolean enabled = Boolean.parseBoolean(parts[2]);
            BreakpointSpec spec = new BreakpointSpec(id++, sourcePath, null, lineNumber, enabled);
            dedupe.put(sourcePath + ":" + lineNumber, spec);
        }

        loaded.addAll(dedupe.values());
        return loaded;
    }

    void save(List<BreakpointSpec> breakpoints) {
        try {
            Files.createDirectories(storagePath.getParent());
            Path temp = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            lines.add(VERSION);

            Map<String, BreakpointSpec> dedupe = new LinkedHashMap<>();
            for (BreakpointSpec breakpoint : breakpoints) {
                dedupe.put(breakpoint.sourcePath() + ":" + breakpoint.line(), breakpoint);
            }

            for (BreakpointSpec breakpoint : dedupe.values()) {
                lines.add(escape(breakpoint.sourcePath()) + "\t" + breakpoint.line() + "\t" + breakpoint.enabled());
            }

            Files.write(temp, lines);
            Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    boolean clear() {
        try {
            return Files.deleteIfExists(storagePath);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                if (c == 't') {
                    builder.append('\t');
                } else if (c == 'n') {
                    builder.append('\n');
                } else {
                    builder.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                builder.append(c);
            }
        }

        if (escaped) {
            builder.append('\\');
        }

        return builder.toString();
    }
}
