package dev.javadebugger.bridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import dev.javadebugger.core.BreakpointSpec;
import dev.javadebugger.core.DebugSourceFile;
import dev.javadebugger.core.DebuggerSession;
import dev.javadebugger.core.DebuggerStop;
import dev.javadebugger.core.LaunchConfig;
import dev.javadebugger.core.StepDirection;

public final class BridgeMain {
    private static final String RECORD_SEP = "\u001e";
    private static final String FIELD_SEP = "\u001f";
    private static final int SOURCE_CONTEXT_LINES = 4;

    private final Object writeLock = new Object();
    private final BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    private final DebuggerSession session = new DebuggerSession(this::emitAsyncStop, null, null);
    private volatile boolean attachMode = false;
    private volatile List<Path> configuredSourceRoots = List.of();

    public static void main(String[] args) throws Exception {
        BridgeMain bridge = new BridgeMain();
        bridge.run();
    }

    private void run() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleLine(line);
            }
        } finally {
            session.close();
        }
    }

    private void handleLine(String line) {
        long id = -1L;
        try {
            Request request = parseRequest(line);
            id = request.id();
            Map<String, String> result = handleCommand(request.command(), request.args());
            sendOk(id, result);
        } catch (Exception exception) {
            sendError(id, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private Map<String, String> handleCommand(String command, Map<String, String> args) throws Exception {
        return switch (command) {
            case "ping" -> mapOf("message", "pong");
            case "attach" -> handleAttach(args);
            case "launch" -> handleLaunch(args);
            case "status" -> statusMap();
            case "continue" -> {
                session.resumeExecution();
                yield mapOf("message", "resumed");
            }
            case "step" -> {
                session.step(parseStepDirection(args.getOrDefault("direction", "over")));
                yield mapOf("message", "step requested");
            }
            case "wait" -> handleWait(args);
            case "breakpoints" -> listBreakpoints();
            case "bp-add" -> addBreakpoint(args);
            case "bp-remove" -> toggleBreakpoint(args, "remove");
            case "bp-enable" -> toggleBreakpoint(args, "enable");
            case "bp-disable" -> toggleBreakpoint(args, "disable");
            case "files" -> listFiles();
            case "file-snippet" -> fileSnippet(args);
            case "source" -> sourceMap();
            case "quit" -> {
                session.close();
                yield mapOf("message", "closed");
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        };
    }

    private Map<String, String> handleAttach(Map<String, String> args) throws Exception {
        String host = args.getOrDefault("host", "127.0.0.1");
        int port = parseInt(args.getOrDefault("port", "5005"), "port");
        List<Path> roots = decodeList(args.get("sourceRoots")).stream()
                .map(Paths::get)
                .toList();

        configuredSourceRoots = normalizeSourceRoots(roots.isEmpty() ? defaultAttachSourceRoots() : roots);
        attachMode = true;
        session.attach(host, port);

        Map<String, String> result = statusMap();
        result.put("message", "attached " + host + ":" + port);
        return result;
    }

    private Map<String, String> handleLaunch(Map<String, String> args) throws Exception {
        String mainClass = require(args, "mainClass");
        String classpath = require(args, "classpath");
        List<String> programArgs = decodeList(args.get("programArgs"));

        attachMode = false;
        configuredSourceRoots = List.of();
        session.start(new LaunchConfig(mainClass, classpath, programArgs));

        Map<String, String> result = statusMap();
        DebuggerStop initial = session.awaitStop(Duration.ofSeconds(15));
        if (initial != null) {
            appendStop(result, initial);
            result.put("summary", describeStop(initial));
        }
        result.put("message", "launched " + mainClass);
        return result;
    }

    private Map<String, String> handleWait(Map<String, String> args) throws Exception {
        long timeoutMs = Long.parseLong(args.getOrDefault("timeoutMs", "1000"));
        DebuggerStop stop = session.awaitStop(Duration.ofMillis(Math.max(1L, timeoutMs)));
        if (stop == null) {
            return mapOf("event", "timeout");
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("event", "stop");
        appendStop(result, stop);
        result.put("summary", describeStop(stop));
        return result;
    }

    private Map<String, String> addBreakpoint(Map<String, String> args) {
        String sourcePath = require(args, "sourcePath");
        int line = parseInt(require(args, "line"), "line");
        String className = args.get("className");

        BreakpointSpec spec = (className == null || className.isBlank())
                ? session.addBreakpoint(sourcePath, line)
                : session.addBreakpoint(sourcePath, className, line);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", Long.toString(spec.id()));
        result.put("enabled", Boolean.toString(spec.enabled()));
        result.put("sourcePath", spec.sourcePath());
        result.put("line", Integer.toString(spec.line()));
        return result;
    }

    private Map<String, String> toggleBreakpoint(Map<String, String> args, String mode) {
        long id = Long.parseLong(require(args, "id"));
        boolean changed = switch (mode) {
            case "remove" -> session.removeBreakpoint(id);
            case "enable" -> session.enableBreakpoint(id);
            case "disable" -> session.disableBreakpoint(id);
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };
        return mapOf("changed", Boolean.toString(changed));
    }

    private Map<String, String> listBreakpoints() {
        List<String> records = new ArrayList<>();
        for (BreakpointSpec breakpoint : session.breakpointList()) {
            records.add(breakpoint.id()
                    + FIELD_SEP + breakpoint.enabled()
                    + FIELD_SEP + breakpoint.line()
                    + FIELD_SEP + nullSafe(breakpoint.sourcePath())
                    + FIELD_SEP + nullSafe(breakpoint.className()));
        }
        return mapOf("records", String.join(RECORD_SEP, records));
    }

    private Map<String, String> listFiles() {
        Path mainSourceRoot = locateMainSourceRoot();
        if (mainSourceRoot == null) {
            return mapOf("records", "");
        }

        Map<String, DebugSourceFile> loadedFiles = new LinkedHashMap<>();
        for (DebugSourceFile file : session.listLoadedSourceFiles()) {
            if (!isProjectRelevantFile(file)) {
                continue;
            }

            String relativePath = relativeSourcePath(mainSourceRoot, file.sourcePath());
            if (relativePath == null) {
                continue;
            }

            loadedFiles.putIfAbsent(relativePath, file);
        }

        List<Path> projectFiles = walkJavaSourceFiles(mainSourceRoot);

        List<String> records = new ArrayList<>();
        for (Path projectFile : projectFiles) {
            String relativePath = relativeSourcePath(mainSourceRoot, projectFile.toString());
            if (relativePath == null) {
                continue;
            }

            DebugSourceFile file = loadedFiles.get(relativePath);
            if (file == null) {
                continue;
            }

            String lines = file.executableLines().stream()
                    .map(String::valueOf)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            records.add(relativePath
                    + FIELD_SEP + nullSafe(file.className())
                    + FIELD_SEP + file.hasMainMethod()
                    + FIELD_SEP + lines);
        }

        return mapOf("records", String.join(RECORD_SEP, records));
    }

    private Map<String, String> fileSnippet(Map<String, String> args) {
        String sourcePath = require(args, "sourcePath");
        int line = parseInt(require(args, "line"), "line");
        int context = parseInt(args.getOrDefault("context", "10"), "context");

        Path mainSourceRoot = locateMainSourceRoot();
        if (mainSourceRoot == null) {
            return mapOf("status", "missing", "message", "Main source root not found.");
        }

        Path file = mainSourceRoot.resolve(sourcePath).normalize();
        if (!file.startsWith(mainSourceRoot) || !Files.exists(file)) {
            return mapOf("status", "missing", "message", "Source file not found under main source root.");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException exception) {
            return mapOf("status", "missing", "message", "Failed to read source file.");
        }

        if (lines.isEmpty()) {
            return mapOf("status", "missing", "message", "Source file is empty.");
        }

        int targetLine = Math.max(1, Math.min(line, lines.size()));
        int ctx = Math.max(1, context);
        int start = Math.max(1, targetLine - ctx);
        int end = Math.min(lines.size(), targetLine + ctx);

        List<String> records = new ArrayList<>();
        for (int current = start; current <= end; current++) {
            records.add(current + FIELD_SEP + lines.get(current - 1));
        }

        return mapOf(
                "status", "ok",
                "path", sourcePath,
                "line", Integer.toString(targetLine),
                "start", Integer.toString(start),
                "end", Integer.toString(end),
                "records", String.join(RECORD_SEP, records));
    }

    private Path locateMainSourceRoot() {
        for (Path root : configuredSourceRoots) {
            Path normalized = root.toAbsolutePath().normalize();
            if (normalized.endsWith(Paths.get("src", "main", "java")) && Files.isDirectory(normalized)) {
                return normalized;
            }
        }

        Path fallback = Paths.get("").toAbsolutePath().normalize().resolve(Paths.get("src", "main", "java"));
        if (Files.isDirectory(fallback)) {
            return fallback.normalize();
        }

        return null;
    }

    private List<Path> walkJavaSourceFiles(Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private String relativeSourcePath(Path sourceRoot, String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }

        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        Path raw = Paths.get(sourcePath);
        Path candidate = raw.isAbsolute() ? raw.normalize() : normalizedRoot.resolve(sourcePath).normalize();
        if (!candidate.startsWith(normalizedRoot) || !Files.exists(candidate)) {
            return null;
        }

        return normalizedRoot.relativize(candidate).toString().replace('\\', '/');
    }

    private boolean isProjectRelevantFile(DebugSourceFile file) {
        String sourcePath = file.sourcePath() == null ? "" : file.sourcePath();
        String className = file.className() == null ? "" : file.className();

        if (sourcePath.isBlank()) {
            return false;
        }

        if (startsWithAny(className,
                "java.",
                "javax.",
                "jdk.",
                "sun.",
                "com.sun.",
                "org.w3c.",
                "org.xml.",
                "kotlin.",
                "scala.",
                "org.springframework.",
                "org.apache.",
                "org.hibernate.",
                "org.slf4j.",
                "ch.qos.logback.",
                "com.fasterxml.",
                "com.google.",
                "jakarta.",
                "io.netty.",
                "reactor.",
                "org.junit.")) {
            return false;
        }

        return !sourcePath.startsWith("META-INF/")
                && !sourcePath.startsWith("module-info")
                && !sourcePath.contains("/target/")
                && !sourcePath.contains("\\target\\");
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> sourceMap() {
        Optional<DebuggerStop> stop = session.currentStop();
        if (stop.isEmpty()) {
            return mapOf("status", "unavailable", "message", "Debugger is running.");
        }

        DebuggerStop current = stop.get();
        if (current.terminated() || "start".equals(current.kind()) || current.lineNumber() <= 0) {
            return mapOf("status", "unavailable", "message", "No active source line.");
        }

        Optional<Path> sourceFile = resolveSourceFile(current);
        if (sourceFile.isEmpty()) {
            return mapOf(
                    "status", "missing",
                    "path", current.sourcePath() == null ? "<unknown>" : current.sourcePath(),
                    "line", Integer.toString(current.lineNumber()),
                    "message", "Runtime stop mapped, local source unavailable.");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile.get());
        } catch (IOException exception) {
            return mapOf(
                    "status", "missing",
                    "path", sourceFile.get().toString(),
                    "line", Integer.toString(current.lineNumber()),
                    "message", "Failed reading source file.");
        }

        int currentLine = current.lineNumber();
        if (lines.isEmpty()) {
            return mapOf(
                    "status", "missing",
                    "path", sourceFile.get().toString(),
                    "line", Integer.toString(currentLine),
                    "message", "Source file is empty.");
        }

        int start = Math.max(1, currentLine - SOURCE_CONTEXT_LINES);
        int end = Math.min(lines.size(), currentLine + SOURCE_CONTEXT_LINES);
        List<String> records = new ArrayList<>();
        for (int line = start; line <= end; line++) {
            String marker = line == currentLine ? "->" : "  ";
            records.add(line + FIELD_SEP + marker + FIELD_SEP + lines.get(line - 1));
        }

        return mapOf(
                "status", "ok",
                "path", sourceFile.get().toString(),
                "line", Integer.toString(currentLine),
                "start", Integer.toString(start),
                "end", Integer.toString(end),
                "records", String.join(RECORD_SEP, records));
    }

    private Map<String, String> statusMap() {
        Optional<DebuggerStop> stop = session.currentStop();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("running", Boolean.toString(session.isRunning()));
        if (stop.isEmpty()) {
            result.put("state", "running");
            result.put("summary", "Running or waiting for first stop.");
            return result;
        }

        DebuggerStop value = stop.get();
        result.put("state", value.terminated() ? "terminated" : "paused");
        appendStop(result, value);
        result.put("summary", describeStop(value));
        return result;
    }

    private void appendStop(Map<String, String> target, DebuggerStop stop) {
        target.put("kind", nullSafe(stop.kind()));
        target.put("breakpointId", Long.toString(stop.breakpointId()));
        target.put("sourcePath", nullSafe(stop.sourcePath()));
        target.put("sourceName", nullSafe(stop.sourceName()));
        target.put("className", nullSafe(stop.className()));
        target.put("line", Integer.toString(stop.lineNumber()));
        target.put("thread", nullSafe(stop.threadName()));
        target.put("terminated", Boolean.toString(stop.terminated()));
    }

    private void emitAsyncStop(DebuggerStop stop) {
        Map<String, String> fields = new LinkedHashMap<>();
        appendStop(fields, stop);
        fields.put("summary", describeStop(stop));
        sendEvent("stop", fields);
    }

    private void sendOk(long id, Map<String, String> fields) {
        sendLine(formatLine(Long.toString(id), "ok", fields));
    }

    private void sendError(long id, String message) {
        sendLine(formatLine(Long.toString(id), "error", mapOf("message", nullSafe(message))));
    }

    private void sendEvent(String eventName, Map<String, String> fields) {
        sendLine(formatLine("event", eventName, fields));
    }

    private void sendLine(String line) {
        synchronized (writeLock) {
            try {
                output.write(line);
                output.newLine();
                output.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private static String formatLine(String first, String second, Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        builder.append(encode(first)).append('|').append(encode(second));
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            builder.append('|')
                    .append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()));
        }
        return builder.toString();
    }

    private static Request parseRequest(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed bridge request");
        }

        long id = Long.parseLong(decode(parts[0]));
        String command = decode(parts[1]);
        Map<String, String> args = new HashMap<>();
        for (int i = 2; i < parts.length; i++) {
            String token = parts[i];
            int split = token.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = decode(token.substring(0, split));
            String value = decode(token.substring(split + 1));
            args.put(key, value);
        }

        return new Request(id, command, args);
    }

    private static String require(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + value);
        }
    }

    private static StepDirection parseStepDirection(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "into" -> StepDirection.INTO;
            case "over" -> StepDirection.OVER;
            case "out" -> StepDirection.OUT;
            default -> throw new IllegalArgumentException("Unknown step direction: " + value);
        };
    }

    private Optional<Path> resolveSourceFile(DebuggerStop stop) {
        if (stop.sourcePath() == null || stop.sourcePath().isBlank()) {
            return Optional.empty();
        }

        if (attachMode && configuredSourceRoots.isEmpty()) {
            return Optional.empty();
        }

        Path raw = Paths.get(stop.sourcePath());
        if (raw.isAbsolute() && Files.exists(raw)) {
            return Optional.of(raw.normalize());
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        if (!attachMode) {
            candidates.add(cwd.resolve(stop.sourcePath()));
            candidates.add(cwd.resolve("src/main/java").resolve(stop.sourcePath()));
            candidates.add(cwd.resolve("src/test/java").resolve(stop.sourcePath()));
        }
        for (Path sourceRoot : configuredSourceRoots) {
            candidates.add(sourceRoot.resolve(stop.sourcePath()));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Optional.of(candidate.normalize());
            }
        }

        String fileName = raw.getFileName() == null ? null : raw.getFileName().toString();
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }

        if (!attachMode) {
            List<Path> searchRoots = new ArrayList<>();
            searchRoots.add(cwd.resolve("src"));
            searchRoots.addAll(configuredSourceRoots);
            for (Path searchRoot : searchRoots) {
                if (!Files.exists(searchRoot)) {
                    continue;
                }
                Optional<Path> found = findFileByName(searchRoot, fileName);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<Path> findFileByName(Path searchRoot, String fileName) {
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            return walk
                    .filter(path -> Files.isRegularFile(path) && fileName.equals(path.getFileName().toString()))
                    .findFirst()
                    .map(Path::normalize);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static List<Path> normalizeSourceRoots(List<Path> roots) {
        return roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private static List<Path> defaultAttachSourceRoots() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> defaults = List.of(cwd.resolve("src/main/java"), cwd.resolve("src/test/java"));
        return defaults.stream().filter(Files::exists).toList();
    }

    private static List<String> decodeList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(RECORD_SEP, -1);
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return values;
    }

    private static String describeStop(DebuggerStop stop) {
        if (stop.terminated()) {
            return "Target process terminated normally.";
        }

        if ("start".equals(stop.kind())) {
            return "Target is suspended at startup on thread " + stop.threadName() + ".";
        }

        StringBuilder output = new StringBuilder();
        output.append("Stopped by ").append(stop.kind());
        if (stop.className() != null) {
            output.append(" at ").append(stop.className());
        }
        if (stop.sourceName() != null) {
            output.append(" (").append(stop.sourceName()).append(":").append(stop.lineNumber()).append(")");
        }
        output.append(" on thread ").append(stop.threadName());
        if (stop.breakpointId() >= 0) {
            output.append(" [breakpoint ").append(stop.breakpointId()).append(']');
        }
        return output.toString();
    }

    private static Map<String, String> mapOf(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have an even length");
        }

        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], nullSafe(keyValues[i + 1]));
        }
        return map;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record Request(long id, String command, Map<String, String> args) {
    }
}
