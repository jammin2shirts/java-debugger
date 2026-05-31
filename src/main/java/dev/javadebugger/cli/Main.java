package dev.javadebugger.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import dev.javadebugger.core.BreakpointSpec;
import dev.javadebugger.core.DebugSourceFile;
import dev.javadebugger.core.DebuggerSession;
import dev.javadebugger.core.DebuggerStop;
import dev.javadebugger.core.LaunchConfig;
import dev.javadebugger.core.StepDirection;

public final class Main {
    private static final Duration WAIT_FOR_STOP_TIMEOUT = Duration.ofMinutes(5);
    private static final BreakpointPersistence BREAKPOINT_PERSISTENCE = BreakpointPersistence.defaultStore();
    private static final String LAUNCH_CONNECTED_MESSAGE = "Connected (launch mode). Target is suspended at startup.";
    private static final String EXECUTION_RESUMED_MESSAGE = "Execution resumed. Waiting for next stop...";
    private static volatile List<Path> configuredSourceRoots = List.of();
    private static volatile boolean attachMode = false;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && isHelp(args[0])) {
            printUsage();
            return;
        }

        SessionPlan sessionPlan = buildSessionPlan(args);

        List<BreakpointSpec> breakpointsForRestart = BREAKPOINT_PERSISTENCE.load();

        while (true) {
            AtomicReference<DebuggerStop> asyncStopRef = new AtomicReference<>();
            try (DebuggerSession session = new DebuggerSession(asyncStopRef::set)) {
                sessionPlan.start(session);
                restoreBreakpoints(session, breakpointsForRestart);

                String startupMessage;
                if (sessionPlan.waitForInitialStop() && !breakpointsForRestart.isEmpty()) {
                    session.resumeExecution();
                    startupMessage = "Auto-continued to first stop. " + awaitNextStop(session, "continue");
                } else if (sessionPlan.waitForInitialStop()) {
                    startupMessage = awaitNextStop(session, "start");
                } else {
                    startupMessage = sessionPlan.initialMessage();
                }
                if (!breakpointsForRestart.isEmpty()) {
                    startupMessage = startupMessage + " Restored breakpoints: " + summarizeBreakpoints(breakpointsForRestart, false);
                }
                asyncStopRef.set(null);
                ShellResult result = runShell(session, startupMessage, asyncStopRef);
                if (!result.restartRequested()) {
                    return;
                }
                breakpointsForRestart = result.breakpoints();
            }
        }
    }

    private static boolean isHelp(String value) {
        return "-h".equals(value) || "--help".equals(value);
    }

    private static boolean containsToken(String[] args, String token) {
        for (String arg : args) {
            if (token.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String[] removeToken(String[] args, String token) {
        List<String> filtered = new ArrayList<>();
        for (String arg : args) {
            if (!token.equals(arg)) {
                filtered.add(arg);
            }
        }
        return filtered.toArray(String[]::new);
    }

    private static SessionPlan buildSessionPlan(String[] args) {
        if (args.length > 0 && "launch".equals(args[0])) {
            LaunchConfig config = parseLaunchConfig(Arrays.copyOfRange(args, 1, args.length));
            return launchSessionPlan(config);
        }

        if (args.length > 0 && "attach".equals(args[0])) {
            AttachConfig config = parseAttachConfig(Arrays.copyOfRange(args, 1, args.length));
            return attachSessionPlan(config);
        }

        if (containsToken(args, "--launch")) {
            LaunchConfig config = parseLaunchConfig(removeToken(args, "--launch"));
            return launchSessionPlan(config);
        }

        // Default mode: attach to localhost:5005 unless attach options override it.
        return attachSessionPlan(parseAttachConfig(args));
    }

    private static SessionPlan launchSessionPlan(LaunchConfig config) {
        configuredSourceRoots = List.of();
        attachMode = false;
        return new SessionPlan(session -> session.start(config), true, LAUNCH_CONNECTED_MESSAGE);
    }

    private static SessionPlan attachSessionPlan(AttachConfig config) {
        List<Path> attachSourceRoots = config.sourceRoots().isEmpty()
                ? defaultAttachSourceRoots()
                : config.sourceRoots();
        configuredSourceRoots = normalizeSourceRoots(attachSourceRoots);
        attachMode = true;
        return new SessionPlan(
                session -> session.attach(config.host(), config.port()),
                false,
                attachStartupMessage(config.host(), config.port(), configuredSourceRoots));
    }

    private static void restoreBreakpoints(DebuggerSession session, List<BreakpointSpec> breakpointsForRestart) {
        for (BreakpointSpec breakpoint : breakpointsForRestart) {
            BreakpointSpec restored = session.addBreakpoint(breakpoint.sourcePath(), breakpoint.line());
            if (!breakpoint.enabled()) {
                session.disableBreakpoint(restored.id());
            }
        }
    }

    private static ShellResult runShell(DebuggerSession session,
                                        String initialMessage,
                                        AtomicReference<DebuggerStop> asyncStopRef) throws IOException {
        Scanner scanner = new Scanner(System.in);
        String lastRepeatableCommand = null;
        String lastMessage = initialMessage;

        while (true) {
            renderInterface(session, lastMessage);
            System.out.print("jdbg> ");
            PromptInput promptInput = readPromptInput(scanner, asyncStopRef);
            if (promptInput.eof()) {
                return new ShellResult(false, List.of());
            }
            if (promptInput.asyncStop() != null) {
                DebuggerStop stop = promptInput.asyncStop();
                lastMessage = "Asynchronous stop detected. " + describeStop(stop);
                lastRepeatableCommand = null;
                continue;
            }

            String rawInput = promptInput.text();
            String line;
            if (rawInput.isEmpty()) {
                if (lastRepeatableCommand == null) {
                    continue;
                }
                line = lastRepeatableCommand;
                System.out.println("(repeat) " + line);
            } else {
                line = expandInput(rawInput, scanner);
                if (line == null || line.isBlank()) {
                    continue;
                }
            }

            String[] tokens = line.split("\\s+");
            String command = tokens[0].toLowerCase(Locale.ROOT);

            switch (command) {
                case "help" -> lastMessage = helpSummary();
                case "breaks" -> lastMessage = printBreakpoints(session);
                case "clear" -> {
                    lastMessage = handleClear(session, tokens);
                    saveBreakpoints(session.breakpointList());
                }
                case "continue", "start" -> {
                    if (isTerminated(session)) {
                        lastMessage = postTerminationMessage();
                        lastRepeatableCommand = null;
                        break;
                    }
                    session.resumeExecution();
                    lastRepeatableCommand = "continue";
                    lastMessage = EXECUTION_RESUMED_MESSAGE;
                    if (isTerminated(session)) {
                        lastRepeatableCommand = null;
                    }
                }
                case "step" -> {
                    if (isTerminated(session)) {
                        lastMessage = postTerminationMessage();
                        lastRepeatableCommand = null;
                        break;
                    }
                    if (session.currentStop().isEmpty()) {
                        lastMessage = "Debugger is running and not paused yet. Wait for a breakpoint to be hit before stepping.";
                        lastRepeatableCommand = null;
                        break;
                    }
                    lastMessage = handleStep(session, tokens);
                    lastRepeatableCommand = line;
                    if (isTerminated(session)) {
                        lastRepeatableCommand = null;
                    }
                }
                case "files" -> {
                    lastMessage = browseFilesAndToggleBreakpoints(session);
                    saveBreakpoints(session.breakpointList());
                }
                case "manage" -> {
                    lastMessage = manageBreakpoints(session);
                    saveBreakpoints(session.breakpointList());
                }
                case "state" -> lastMessage = handleStateCommand(tokens);
                case "status" -> lastMessage = describeCurrentStop(session.currentStop());
                case "restart" -> {
                    saveBreakpoints(session.breakpointList());
                    return new ShellResult(true, session.breakpointList());
                }
                case "quit", "exit" -> {
                    saveBreakpoints(session.breakpointList());
                    return new ShellResult(false, List.of());
                }
                default -> lastMessage = "Unknown command: " + command;
            }
        }
    }

    private static PromptInput readPromptInput(Scanner scanner,
                                               AtomicReference<DebuggerStop> asyncStopRef) throws IOException {
        if (System.console() == null) {
            if (!scanner.hasNextLine()) {
                return PromptInput.endOfInput();
            }
            return PromptInput.ofText(scanner.nextLine().trim());
        }

        try (TerminalMode ignored = TerminalMode.enterRaw()) {
            StringBuilder buffer = new StringBuilder();
            while (true) {
                DebuggerStop asyncStop = asyncStopRef.getAndSet(null);
                if (asyncStop != null) {
                    return PromptInput.ofAsyncStop(asyncStop);
                }

                int key = readByteWithTimeout(50);
                if (key == -1) {
                    continue;
                }

                if (key == 27) {
                    NavKey navKey = readEscapedNavKey();
                    if (navKey == NavKey.UP) {
                        System.out.println();
                        return PromptInput.ofText("step into");
                    }
                    if (navKey == NavKey.DOWN) {
                        System.out.println();
                        return PromptInput.ofText("continue");
                    }
                    if (navKey == NavKey.RIGHT) {
                        System.out.println();
                        return PromptInput.ofText("step over");
                    }
                    if (navKey == NavKey.LEFT) {
                        System.out.println();
                        return PromptInput.ofText("step out");
                    }
                    continue;
                }

                if (key == '\n' || key == '\r') {
                    System.out.println();
                    return PromptInput.ofText(buffer.toString().trim());
                }

                if (key == 127 || key == 8) {
                    if (!buffer.isEmpty()) {
                        buffer.deleteCharAt(buffer.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                    continue;
                }

                if (key >= 32 && key != 127) {
                    buffer.append((char) key);
                    System.out.print((char) key);
                    System.out.flush();
                }
            }
        }
    }

    private static void renderInterface(DebuggerSession session, String message) {
        clearScreen();
        System.out.println("Java Debugger (TUI)");
        System.out.println("===================");
        System.out.println("Status: " + describeCurrentStop(session.currentStop()));
        if (message != null && !message.isBlank()) {
            System.out.println("Message: " + message);
        }
        System.out.println();
        renderBreakpointsPane(session.breakpointList());
        System.out.println();
        renderSourcePane(session.currentStop());
        System.out.println();
        printCommandBar();
    }

    private static void renderBreakpointsPane(List<BreakpointSpec> breakpoints) {
        System.out.println("Breakpoints:");
        if (breakpoints.isEmpty()) {
            System.out.println("  <none>");
            return;
        }

        for (BreakpointSpec breakpoint : breakpoints) {
            String enabled = breakpoint.enabled() ? "enabled" : "disabled";
            System.out.println("  " + breakpoint.id() + " [" + enabled + "] -> " + breakpoint.sourcePath() + ":" + breakpoint.line());
        }
    }

    private static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static void printCommandBar() {
        System.out.println("Commands: [↓] start/continue  [↑] step into  [→] step over  [←] step out  [x] clear  [f] files  [m] manage breakpoints  [s] status  [l] breaks  [r] restart  [state clear] clear saved  [h] help  [q] quit  [enter] repeat");
    }

    private static String expandInput(String rawInput, Scanner scanner) {
        return switch (rawInput.toLowerCase(Locale.ROOT)) {
            case "c" -> "continue";
            case "start" -> "continue";
            case "i" -> "step into";
            case "o" -> "step over";
            case "u" -> "step out";
            case "\u001b[a" -> "step into";
            case "\u001b[c" -> "step over";
            case "\u001b[d" -> "step out";
            case "\u001b[b" -> "continue";
            case "s" -> "status";
            case "l" -> "breaks";
            case "f" -> "files";
            case "m" -> "manage";
            case "r" -> "restart";
            case "q" -> "quit";
            case "h", "?" -> "help";
            case "x" -> {
                System.out.print("breakpoint id: ");
                if (!scanner.hasNextLine()) {
                    yield null;
                }
                String value = scanner.nextLine().trim();
                if (value.isEmpty()) {
                    yield null;
                }
                yield "clear " + value;
            }
            default -> rawInput;
        };
    }

    private static String handleClear(DebuggerSession session, String[] tokens) {
        if (tokens.length != 2) {
            return "Usage: clear <breakpoint-id>";
        }

        try {
            long id = Long.parseLong(tokens[1]);
            if (session.removeBreakpoint(id)) {
                return "Breakpoint " + id + " removed.";
            } else {
                return "No breakpoint " + id + " found.";
            }
        } catch (NumberFormatException exception) {
            return "Invalid breakpoint id: " + tokens[1];
        }
    }

    private static String handleStep(DebuggerSession session, String[] tokens) {
        if (tokens.length != 2) {
            return "Usage: step into|over|out";
        }

        StepDirection direction = switch (tokens[1].toLowerCase(Locale.ROOT)) {
            case "into" -> StepDirection.INTO;
            case "over" -> StepDirection.OVER;
            case "out" -> StepDirection.OUT;
            default -> null;
        };

        if (direction == null) {
            return "Usage: step into|over|out";
        }

        try {
            session.step(direction);
            return awaitNextStop(session, "step");
        } catch (IllegalStateException exception) {
            return "Debugger is not paused yet. Wait for the first stop before stepping.";
        }
    }

    private static String printBreakpoints(DebuggerSession session) {
        List<BreakpointSpec> breakpoints = session.breakpointList();
        if (breakpoints.isEmpty()) {
            return "No breakpoints set.";
        }

        return summarizeBreakpoints(breakpoints, true);
    }

    private static String summarizeBreakpoints(List<BreakpointSpec> breakpoints, boolean includeIds) {
        StringBuilder builder = new StringBuilder();
        for (BreakpointSpec breakpoint : breakpoints) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }

            if (includeIds) {
                builder.append(breakpoint.id()).append(" -> ");
            }

            builder.append(breakpoint.sourcePath()).append(":").append(breakpoint.line());
            if (!breakpoint.enabled()) {
                builder.append(" (disabled)");
            }
        }
        return builder.toString();
    }

    private static String helpSummary() {
        return "Use arrows at prompt (↑/→/←/↓) for stepping and continue; use f to set breakpoints from files, m to manage breakpoints, r to restart, state clear to reset saved breakpoints, and enter to repeat last continue/step.";
    }

    private static String handleStateCommand(String[] tokens) {
        if (tokens.length == 2 && "clear".equalsIgnoreCase(tokens[1])) {
            return BREAKPOINT_PERSISTENCE.clear()
                    ? "Cleared saved breakpoints from " + BREAKPOINT_PERSISTENCE.storagePath()
                    : "No saved breakpoint state found at " + BREAKPOINT_PERSISTENCE.storagePath();
        }
        return "Usage: state clear";
    }

    private static void saveBreakpoints(List<BreakpointSpec> breakpoints) {
        try {
            BREAKPOINT_PERSISTENCE.save(breakpoints);
        } catch (RuntimeException exception) {
            System.err.println("Warning: failed to save breakpoints to " + BREAKPOINT_PERSISTENCE.storagePath() + ": " + exception.getMessage());
        }
    }

    private static boolean isTerminated(DebuggerSession session) {
        return session.currentStop().map(DebuggerStop::terminated).orElse(false);
    }

    private static String postTerminationMessage() {
        return "Program has already ended. You cannot continue running it. You can manage breakpoints or restart the process.";
    }

    private static String browseFilesAndToggleBreakpoints(DebuggerSession session) {
        List<FileEntry> files = listFileEntries(session);
        if (files.isEmpty()) {
            return "No source files available from the target JVM or local src/.";
        }

        try (TerminalMode ignored = TerminalMode.enterRaw()) {
            int fileIndex = 0;
            while (true) {
                clearScreen();
                System.out.println("File Picker (up/down move, enter open, q exit)");
                System.out.println();
                for (int i = 0; i < files.size(); i++) {
                    String marker = i == fileIndex ? ">" : " ";
                    System.out.println(marker + " " + files.get(i).displayPath());
                }

                NavKey key = readNavKey();
                switch (key) {
                    case UP -> fileIndex = Math.max(0, fileIndex - 1);
                    case DOWN -> fileIndex = Math.min(files.size() - 1, fileIndex + 1);
                    case ENTER -> {
                        String message = editFileBreakpoints(session, files.get(fileIndex));
                        if (message != null) {
                            return message;
                        }
                    }
                    case QUIT -> {
                        return "Exited file picker.";
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static String editFileBreakpoints(DebuggerSession session, FileEntry file) {
        if (file.localPath() == null || !Files.exists(file.localPath())) {
            return editRemoteFileBreakpoints(session, file);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file.localPath());
        } catch (IOException exception) {
            return "Unable to read file: " + file.displayPath();
        }

        if (lines.isEmpty()) {
            return "File is empty: " + file.displayPath();
        }

        int cursor = 0;
        String lastAction = "Use space to toggle breakpoint on the highlighted line.";
        while (true) {
            clearScreen();
            System.out.println("Line Picker: " + file.displayPath());
            System.out.println("(up/down move, space toggle breakpoint, enter/q back)");
            System.out.println("Message: " + lastAction);
            System.out.println();

            int start = Math.max(0, cursor - 8);
            int end = Math.min(lines.size() - 1, cursor + 8);
            for (int i = start; i <= end; i++) {
                int lineNumber = i + 1;
                BreakpointSpec existing = findBreakpoint(session.breakpointList(), file.sourcePath(), lineNumber);
                String marker = i == cursor ? ">" : " ";
                String bp = existing == null ? "   " : (existing.enabled() ? "[*]" : "[ ]");
                System.out.printf("%s %s %4d | %s%n", marker, bp, lineNumber, lines.get(i));
            }

            NavKey key = readNavKey();
            switch (key) {
                case UP -> cursor = Math.max(0, cursor - 1);
                case DOWN -> cursor = Math.min(lines.size() - 1, cursor + 1);
                case SPACE -> {
                    int lineNumber = cursor + 1;
                    BreakpointSpec existing = findBreakpoint(session.breakpointList(), file.sourcePath(), lineNumber);
                    if (existing == null) {
                        BreakpointSpec created = session.addBreakpoint(file.sourcePath(), file.classNameHint(), lineNumber);
                        lastAction = "Added breakpoint " + created.id() + " at line " + lineNumber;
                    } else {
                        session.removeBreakpoint(existing.id());
                        lastAction = "Removed breakpoint " + existing.id() + " from line " + lineNumber;
                    }
                }
                case ENTER, QUIT -> {
                    return "Updated breakpoints for " + file.displayPath();
                }
                default -> {
                }
            }
        }
    }

    private static String editRemoteFileBreakpoints(DebuggerSession session, FileEntry file) {
        List<Integer> executableLines = file.executableLines();
        if (executableLines.isEmpty()) {
            return "No executable line metadata available for " + file.displayPath() + ".";
        }

        int cursor = 0;
        String lastAction = "Use space to toggle breakpoint on the highlighted executable line.";
        while (true) {
            clearScreen();
            System.out.println("Line Picker: " + file.displayPath());
            System.out.println("(target JVM metadata mode; up/down move, space toggle breakpoint, enter/q back)");
            System.out.println("Message: " + lastAction);
            System.out.println();

            int start = Math.max(0, cursor - 8);
            int end = Math.min(executableLines.size() - 1, cursor + 8);
            for (int i = start; i <= end; i++) {
                int lineNumber = executableLines.get(i);
                BreakpointSpec existing = findBreakpoint(session.breakpointList(), file.sourcePath(), lineNumber);
                String marker = i == cursor ? ">" : " ";
                String bp = existing == null ? "   " : (existing.enabled() ? "[*]" : "[ ]");
                System.out.printf("%s %s line %d%n", marker, bp, lineNumber);
            }

            NavKey key = readNavKey();
            switch (key) {
                case UP -> cursor = Math.max(0, cursor - 1);
                case DOWN -> cursor = Math.min(executableLines.size() - 1, cursor + 1);
                case SPACE -> {
                    int lineNumber = executableLines.get(cursor);
                    BreakpointSpec existing = findBreakpoint(session.breakpointList(), file.sourcePath(), lineNumber);
                    if (existing == null) {
                        BreakpointSpec created = session.addBreakpoint(file.sourcePath(), file.classNameHint(), lineNumber);
                        lastAction = "Added breakpoint " + created.id() + " at line " + lineNumber;
                    } else {
                        session.removeBreakpoint(existing.id());
                        lastAction = "Removed breakpoint " + existing.id() + " from line " + lineNumber;
                    }
                }
                case ENTER, QUIT -> {
                    return "Updated breakpoints for " + file.displayPath();
                }
                default -> {
                }
            }
        }
    }

    private static String manageBreakpoints(DebuggerSession session) {
        String lastAction = "Space toggles, d delete, a enable all, n disable all, x delete all.";
        int index = 0;

        try (TerminalMode ignored = TerminalMode.enterRaw()) {
            while (true) {
                List<BreakpointSpec> breakpoints = session.breakpointList();
                if (breakpoints.isEmpty()) {
                    return "No breakpoints to manage.";
                }

                index = Math.min(index, breakpoints.size() - 1);
                clearScreen();
                System.out.println("Breakpoint Manager (up/down move, space toggle, d delete, a enable all, n disable all, x delete all, enter/q back)");
                System.out.println("Message: " + lastAction);
                System.out.println();

                for (int i = 0; i < breakpoints.size(); i++) {
                    BreakpointSpec breakpoint = breakpoints.get(i);
                    String marker = i == index ? ">" : " ";
                    String status = breakpoint.enabled() ? "enabled " : "disabled";
                    System.out.printf("%s %3d [%s] %s:%d%n", marker, breakpoint.id(), status, breakpoint.sourcePath(), breakpoint.line());
                }

                NavKey key = readNavKey();
                switch (key) {
                    case UP -> index = Math.max(0, index - 1);
                    case DOWN -> index = Math.min(breakpoints.size() - 1, index + 1);
                    case SPACE -> {
                        BreakpointSpec selected = breakpoints.get(index);
                        if (selected.enabled()) {
                            session.disableBreakpoint(selected.id());
                            lastAction = "Disabled breakpoint " + selected.id();
                        } else {
                            session.enableBreakpoint(selected.id());
                            lastAction = "Enabled breakpoint " + selected.id();
                        }
                    }
                    case DELETE -> {
                        BreakpointSpec selected = breakpoints.get(index);
                        session.removeBreakpoint(selected.id());
                        lastAction = "Deleted breakpoint " + selected.id();
                    }
                    case ENABLE_ALL -> {
                        int changed = session.enableAllBreakpoints();
                        lastAction = "Enabled " + changed + " breakpoint(s).";
                    }
                    case DISABLE_ALL -> {
                        int changed = session.disableAllBreakpoints();
                        lastAction = "Disabled " + changed + " breakpoint(s).";
                    }
                    case DELETE_ALL -> {
                        int removed = session.removeAllBreakpoints();
                        return "Deleted " + removed + " breakpoint(s).";
                    }
                    case ENTER, QUIT -> {
                        return "Exited breakpoint manager.";
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static int runStty(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/sh", "-c", command).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
        }
        return exitCode;
    }

    private static String runSttyAndRead(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/sh", "-c", command).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            return null;
        }
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    private static List<FileEntry> listFileEntries(DebuggerSession session) {
        List<DebugSourceFile> targetFiles = session.listLoadedSourceFiles();
        if (!targetFiles.isEmpty()) {
            List<FileEntry> mappedTargetFiles = targetFiles.stream()
                    .map(file -> new FileEntry(
                    file.sourcePath(),
                            file.sourcePath(),
                            resolveSourcePathIfPresent(file.sourcePath()).orElse(null),
                            file.className(),
                            file.executableLines(),
                            file.hasMainMethod()))
                .toList();

            List<FileEntry> projectFiles = mappedTargetFiles.stream()
                .filter(file -> !isLikelyDependencyClass(file.classNameHint(), file.sourcePath()))
                .map(file -> {
                Path localPath = file.localPath();
                String displayPath = localPath == null ? file.sourcePath() : toProjectDisplayPath(localPath);
                        return new FileEntry(displayPath, file.sourcePath(), localPath, file.classNameHint(), file.executableLines(), file.hasMainMethod());
                })
                    .sorted(Comparator.comparing(FileEntry::displayPath))
                    .toList();

            String appPackage = detectMainApplicationPackage(projectFiles);
            if (appPackage != null) {
                List<FileEntry> packageScopedFiles = projectFiles.stream()
                        .filter(file -> classBelongsToPackage(file.classNameHint(), appPackage))
                        .toList();
                if (!packageScopedFiles.isEmpty()) {
                    return packageScopedFiles;
                }
            }

            if (!projectFiles.isEmpty()) {
            return projectFiles;
            }

            return mappedTargetFiles.stream()
                .map(file -> {
                Path localPath = file.localPath();
                String displayPath = localPath == null ? file.sourcePath() : toProjectDisplayPath(localPath);
                        return new FileEntry(displayPath, file.sourcePath(), localPath, file.classNameHint(), file.executableLines(), file.hasMainMethod());
                })
                .sorted(Comparator.comparing(FileEntry::displayPath))
                .toList();
        }

        return listProjectSourceFiles();
    }

    private static List<FileEntry> listProjectSourceFiles() {
        Path root = Paths.get("src");
        if (!Files.exists(root)) {
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> new FileEntry(
                            path.toString(),
                            path.toString(),
                            path,
                            null,
                            List.<Integer>of(),
                            false))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static BreakpointSpec findBreakpoint(List<BreakpointSpec> breakpoints, String sourcePath, int lineNumber) {
        String normalizedTarget = sourcePath.replace('\\', '/');
        for (BreakpointSpec breakpoint : breakpoints) {
            if (breakpoint.line() != lineNumber) {
                continue;
            }

            String breakpointSourcePath = breakpoint.sourcePath().replace('\\', '/');
            if (breakpointSourcePath.equals(normalizedTarget) || normalizedTarget.endsWith(breakpointSourcePath) || breakpointSourcePath.endsWith(normalizedTarget)) {
                return breakpoint;
            }
        }
        return null;
    }

    private static Optional<Path> resolveSourcePathIfPresent(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }

        Path raw = Paths.get(sourcePath);
        if (raw.isAbsolute() && Files.exists(raw)) {
            return Optional.of(raw.normalize());
        }

        return resolveSourceFile(DebuggerStop.of("breakpoint", -1L, sourcePath, null, null, 1, "unknown", false));
    }

    private static String toProjectDisplayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (normalized.startsWith(cwd)) {
            return cwd.relativize(normalized).toString();
        }
        return normalized.toString();
    }

    private static boolean isLikelyDependencyClass(String className, String sourcePath) {
        String name = className == null ? "" : className;
        String path = sourcePath == null ? "" : sourcePath;

        return startsWithAny(name,
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
                "org.junit.")
                || path.startsWith("META-INF/")
                || path.startsWith("module-info");
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String detectMainApplicationPackage(List<FileEntry> files) {
        for (FileEntry file : files) {
            if (!file.hasMainMethod() || file.classNameHint() == null || file.classNameHint().isBlank()) {
                continue;
            }
            int lastDot = file.classNameHint().lastIndexOf('.');
            if (lastDot > 0) {
                return file.classNameHint().substring(0, lastDot);
            }
        }
        return null;
    }

    private static boolean classBelongsToPackage(String className, String packageName) {
        if (className == null || className.isBlank()) {
            return false;
        }
        return className.equals(packageName)
                || className.startsWith(packageName + ".")
                || className.startsWith(packageName + "$");
    }

    private static NavKey readNavKey() {
        try {
            int key = System.in.read();
            if (key == 27) {
                NavKey navKey = readEscapedNavKey();
                if (navKey == NavKey.UP || navKey == NavKey.DOWN) {
                    return navKey;
                }
                return NavKey.UNKNOWN;
            }

            return switch (key) {
                case 'k', 'K' -> NavKey.UP;
                case 'j', 'J' -> NavKey.DOWN;
                case ' ' -> NavKey.SPACE;
                case 'q', 'Q' -> NavKey.QUIT;
                case 'd', 'D' -> NavKey.DELETE;
                case 'a', 'A' -> NavKey.ENABLE_ALL;
                case 'n', 'N' -> NavKey.DISABLE_ALL;
                case 'x', 'X' -> NavKey.DELETE_ALL;
                case '\n', '\r' -> NavKey.ENTER;
                default -> NavKey.UNKNOWN;
            };
        } catch (IOException exception) {
            return NavKey.UNKNOWN;
        }
    }

    private static NavKey readEscapedNavKey() throws IOException {
        int second = readByteWithTimeout(30);
        if (second == -1) {
            return NavKey.UNKNOWN;
        }

        if (second == 'O') {
            int finalByte = readByteWithTimeout(30);
            return mapArrowFinalByte(finalByte);
        }

        if (second != '[') {
            return NavKey.UNKNOWN;
        }

        while (true) {
            int value = readByteWithTimeout(30);
            if (value == -1) {
                return NavKey.UNKNOWN;
            }

            if ((value >= '0' && value <= '9') || value == ';') {
                continue;
            }

            return mapArrowFinalByte(value);
        }
    }

    private static NavKey mapArrowFinalByte(int value) {
        return switch (value) {
            case 'A' -> NavKey.UP;
            case 'B' -> NavKey.DOWN;
            case 'C' -> NavKey.RIGHT;
            case 'D' -> NavKey.LEFT;
            default -> NavKey.UNKNOWN;
        };
    }

    private static int readByteWithTimeout(int timeoutMillis) throws IOException {
        long deadlineNanos = System.nanoTime() + (timeoutMillis * 1_000_000L);
        while (System.nanoTime() < deadlineNanos) {
            if (System.in.available() > 0) {
                return System.in.read();
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        if (System.in.available() > 0) {
            return System.in.read();
        }

        return -1;
    }

    private static String awaitNextStop(DebuggerSession session, String actionLabel) {
        try {
            DebuggerStop stop = session.awaitStop(WAIT_FOR_STOP_TIMEOUT);
            if (stop == null) {
                return "Execution continued past the current stop; no breakpoint hit yet. Use status to check or wait for the next stop.";
            }

            String stopDescription = describeStop(stop);
            if (stop.terminated()) {
                return stopDescription + " You cannot continue running it. You can manage breakpoints or restart the process.";
            }

            if ("continue".equals(actionLabel) && "breakpoint".equals(stop.kind())) {
                return "Breakpoint hit after continue. " + stopDescription;
            }

            if ("step".equals(actionLabel) && "step".equals(stop.kind())) {
                return "Step completed. " + stopDescription;
            }

            return stopDescription;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for debugger stop.";
        }
    }

    private static LaunchConfig parseLaunchConfig(String[] args) {
        String mainClass = null;
        String classpath = null;
        List<String> programArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            switch (token) {
                case "--main-class" -> {
                    ensureHasValue(args, i, token);
                    mainClass = args[++i];
                }
                case "--classpath" -> {
                    ensureHasValue(args, i, token);
                    classpath = args[++i];
                }
                case "--arg" -> {
                    ensureHasValue(args, i, token);
                    programArgs.add(args[++i]);
                }
                case "-h", "--help" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown launch option: " + token);
            }
        }

        if (mainClass == null || classpath == null) {
            throw new IllegalArgumentException("launch requires --main-class and --classpath");
        }

        return new LaunchConfig(mainClass, classpath, programArgs);
    }

    private static AttachConfig parseAttachConfig(String[] args) {
        String host = "127.0.0.1";
        int port = 5005;
        List<Path> sourceRoots = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            switch (token) {
                case "--host" -> {
                    ensureHasValue(args, i, token);
                    host = args[++i];
                }
                case "--port" -> {
                    ensureHasValue(args, i, token);
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Invalid attach port: " + args[i]);
                    }
                }
                case "--source-root" -> {
                    ensureHasValue(args, i, token);
                    sourceRoots.add(Paths.get(args[++i]));
                }
                case "-h", "--help" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown attach option: " + token);
            }
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("attach port must be in range 1-65535");
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("attach host cannot be blank");
        }

        return new AttachConfig(host, port, sourceRoots);
    }

    private static void ensureHasValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
    }

    private static String describeCurrentStop(Optional<DebuggerStop> currentStop) {
        return currentStop.map(Main::describeStop).orElse("Running or waiting for first stop.");
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

    private static void renderSourcePane(Optional<DebuggerStop> stop) {
        if (stop.isEmpty()) {
            System.out.println("Source: <unavailable while running>");
            return;
        }

        DebuggerStop current = stop.get();
        if (current.terminated() || "start".equals(current.kind()) || current.lineNumber() <= 0) {
            System.out.println("Source: <no current source line>");
            return;
        }

        printSourceSnippet(current);
    }

    private static void printSourceSnippet(DebuggerStop stop) {
        if (stop.lineNumber() <= 0) {
            return;
        }

        Optional<Path> sourceFile = resolveSourceFile(stop);
        if (sourceFile.isEmpty()) {
            String sourceLabel = stop.sourceName() != null ? stop.sourceName() : "<unknown source>";
            System.out.println("Source: " + sourceLabel + ":" + stop.lineNumber());
            System.out.println("-> Runtime is stopped at this mapped line. Local source text is unavailable.");
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile.get());
        } catch (IOException exception) {
            System.out.println("Source: " + sourceFile.get() + ":" + stop.lineNumber());
            System.out.println("-> Runtime is stopped at this mapped line. Source text could not be read.");
            return;
        }

        int currentLine = stop.lineNumber();
        if (lines.isEmpty()) {
            System.out.println("Source: " + sourceFile.get() + ":" + currentLine);
            System.out.println("-> Runtime is stopped at this mapped line. Source file is empty.");
            return;
        }

        if (currentLine > lines.size()) {
            int startLine = Math.max(1, lines.size() - 2);
            int endLine = lines.size();
            int gutterWidth = Integer.toString(currentLine).length();

            System.out.println("Source: " + sourceFile.get() + ":" + currentLine);
            for (int line = startLine; line <= endLine; line++) {
                String lineText = lines.get(line - 1);
                System.out.printf("   %" + gutterWidth + "d | %s%n", line, lineText);
            }
            System.out.printf("-> %" + gutterWidth + "d | <runtime mapped location (method epilogue/closing brace)>%n", currentLine);
            return;
        }

        int startLine = Math.max(1, currentLine - 2);
        int endLine = Math.min(lines.size(), currentLine + 2);
        int gutterWidth = Integer.toString(endLine).length();

        System.out.println("Source: " + sourceFile.get() + ":" + currentLine);
        for (int line = startLine; line <= endLine; line++) {
            String marker = line == currentLine ? "->" : "  ";
            String lineText = lines.get(line - 1);
            System.out.printf("%s %" + gutterWidth + "d | %s%n", marker, line, lineText);
        }
    }

    private static Optional<Path> resolveSourceFile(DebuggerStop stop) {
        if (stop.sourcePath() == null || stop.sourcePath().isBlank()) {
            return Optional.empty();
        }

        // In attach mode, do not probe arbitrary local paths. Use only explicit source roots.
        if (attachMode && configuredSourceRoots.isEmpty()) {
            return Optional.empty();
        }

        Path raw = Paths.get(stop.sourcePath());
        if (raw.isAbsolute() && Files.exists(raw)) {
            return Optional.of(raw.normalize());
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> effectiveSourceRoots = new ArrayList<>(configuredSourceRoots);

        List<Path> candidates = new ArrayList<>();
        if (!attachMode) {
            candidates.add(cwd.resolve(stop.sourcePath()));
            candidates.add(cwd.resolve("src/main/java").resolve(stop.sourcePath()));
            candidates.add(cwd.resolve("src/test/java").resolve(stop.sourcePath()));
        }
        for (Path sourceRoot : effectiveSourceRoots) {
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
            searchRoots.addAll(effectiveSourceRoots);
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

    private static List<Path> normalizeSourceRoots(List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return List.of();
        }
        return sourceRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private static List<Path> defaultAttachSourceRoots() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> defaults = new ArrayList<>();
        Path mainJava = cwd.resolve("src/main/java");
        Path testJava = cwd.resolve("src/test/java");
        if (Files.exists(mainJava)) {
            defaults.add(mainJava);
        }
        if (Files.exists(testJava)) {
            defaults.add(testJava);
        }
        return defaults;
    }

    private static String attachStartupMessage(String host, int port, List<Path> sourceRoots) {
        if (sourceRoots.isEmpty()) {
            return "Attached to " + host + ":" + port + ". Set breakpoints and wait for a stop.";
        }
        String roots = sourceRoots.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(", "));
        return "Attached to " + host + ":" + port + ". Using source roots: " + roots + ".";
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar java-debugger.jar [--host <host>] [--port <port>] [--source-root <path> ...]");
        System.out.println("    Defaults to attach mode at 127.0.0.1:5005");
        System.out.println("  java -jar java-debugger.jar --launch --main-class <class> --classpath <paths> [--arg <value> ...]");
        System.out.println("  java -jar java-debugger.jar launch --main-class <class> --classpath <paths> [--arg <value> ...]");
        System.out.println("  java -jar java-debugger.jar attach [--host <host>] [--port <port>] [--source-root <path> ...]");
    }

    private enum NavKey {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        ENTER,
        SPACE,
        QUIT,
        DELETE,
        ENABLE_ALL,
        DISABLE_ALL,
        DELETE_ALL,
        UNKNOWN
    }

    private static final class TerminalMode implements AutoCloseable {
        private final String originalConfig;
        private final boolean active;

        private TerminalMode(String originalConfig, boolean active) {
            this.originalConfig = originalConfig;
            this.active = active;
        }

        private static TerminalMode enterRaw() {
            if (System.console() == null) {
                return new TerminalMode(null, false);
            }

            try {
                String original = runSttyAndRead("stty -g < /dev/tty");
                if (original == null || original.isBlank()) {
                    return new TerminalMode(null, false);
                }

                int exitCode = runStty("stty -icanon -echo min 1 time 0 < /dev/tty");
                if (exitCode != 0) {
                    return new TerminalMode(null, false);
                }

                return new TerminalMode(original, true);
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return new TerminalMode(null, false);
            }
        }

        @Override
        public void close() {
            if (!active || originalConfig == null || originalConfig.isBlank()) {
                return;
            }

            try {
                runStty("stty " + originalConfig + " < /dev/tty");
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record ShellResult(boolean restartRequested, List<BreakpointSpec> breakpoints) {
    }

    @FunctionalInterface
    private interface SessionStarter {
        void start(DebuggerSession session) throws Exception;
    }

    private record SessionPlan(SessionStarter starter, boolean waitForInitialStop, String initialMessage) {
        private void start(DebuggerSession session) throws Exception {
            starter.start(session);
        }
    }

    private record AttachConfig(String host, int port, List<Path> sourceRoots) {
    }

    private record FileEntry(String displayPath, String sourcePath, Path localPath, String classNameHint,
                             List<Integer> executableLines, boolean hasMainMethod) {
    }

    private record PromptInput(String text, DebuggerStop asyncStop, boolean eof) {
        private static PromptInput ofText(String text) {
            return new PromptInput(text, null, false);
        }

        private static PromptInput ofAsyncStop(DebuggerStop stop) {
            return new PromptInput("", stop, false);
        }

        private static PromptInput endOfInput() {
            return new PromptInput("", null, true);
        }
    }
}
