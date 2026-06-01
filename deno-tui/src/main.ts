import chalk from "npm:chalk@5.3.0";
import figlet from "npm:figlet@1.8.0";
import boxen from "npm:boxen@8.0.1";
import { BridgeClient, type BridgeEvent } from "./bridge_client.ts";

const RECORD_SEP = "\u001e";
const FIELD_SEP = "\u001f";

type AppMode =
  | { kind: "attach"; host: string; port: number; sourceRoots: string[] }
  | { kind: "launch"; mainClass: string; classpath: string; programArgs: string[] };

type BreakpointRow = {
  id: number;
  enabled: boolean;
  line: number;
  sourcePath: string;
};

type SourceLine = {
  line: number;
  marker: string;
  text: string;
};

type DebugFile = {
  sourcePath: string;
  className: string;
  hasMainMethod: boolean;
  executableLines: number[];
};

type SnippetLine = {
  line: number;
  text: string;
};

type FileSnippet = {
  path: string;
  line: number;
  lines: SnippetLine[];
};

type KeyInput =
  | { kind: "up" | "down" | "left" | "right" | "enter" | "escape" | "space" | "char" | "backspace"; value?: string }
  | { kind: "other" };

type DashboardState = {
  status: string;
  activity: string;
  breakpoints: BreakpointRow[];
  sourcePath: string;
  sourceLine: number;
  sourceLines: SourceLine[];
};

const THIS_FILE = new URL(import.meta.url);
const REPO_ROOT = new URL("../..", THIS_FILE).pathname;
let pendingEscapeSequence = false;
const encoder = new TextEncoder();

const state: DashboardState = {
  status: "Initializing...",
  activity: "",
  breakpoints: [],
  sourcePath: "",
  sourceLine: -1,
  sourceLines: [],
};

function clearScreen(): void {
  console.log("\x1b[H\x1b[2J\x1b[3J");
}

function titleBanner(): string {
  const raw = figlet.textSync("JDBG", { font: "ANSI Shadow", horizontalLayout: "default" });
  const lines = raw.split("\n");
  const paints = [chalk.magentaBright, chalk.cyanBright, chalk.blueBright, chalk.greenBright, chalk.yellowBright, chalk.redBright];
  return lines.map((line: string, i: number) => paints[i % paints.length](line)).join("\n");
}

function printSection(title: string, body: string[]): void {
  const content = [chalk.bold.blue(` ${title} `), ...body].join("\n");
  console.log(boxen(content, {
    borderColor: "blue",
    padding: { top: 0, right: 1, bottom: 0, left: 1 },
    margin: { top: 0, right: 0, bottom: 1, left: 0 },
  }));
}

function renderDashboard(): void {
  clearScreen();
  console.log(titleBanner());
  console.log(chalk.bold.cyan("Deno TUI + Java JDI engine"));
  console.log(chalk.dim("Same debugger core, redesigned JS runtime interface"));
  console.log();

  printSection("STATUS", [chalk.greenBright(state.status)]);
  printSection("ACTIVITY", [chalk.yellowBright(state.activity || "No activity yet")]);

  const breakpointsBody = state.breakpoints.length === 0
    ? [chalk.gray("<none>")]
    : state.breakpoints.slice(0, 12).map((bp) => {
      const enabled = bp.enabled ? chalk.green("enabled ") : chalk.yellow("disabled");
      return `${String(bp.id).padStart(3, " ")} [${enabled}] ${bp.sourcePath}:${bp.line}`;
    });
  printSection("BREAKPOINTS", breakpointsBody);

  const sourceBody = state.sourcePath
    ? [chalk.cyanBright(`${state.sourcePath}:${state.sourceLine}`), ...state.sourceLines.map((row) => {
      const marker = row.marker === "->" ? chalk.redBright.bold("->") : "  ";
      return `${marker} ${String(row.line).padStart(4, " ")} | ${row.text}`;
    })]
    : [chalk.gray("No source lines available")];
  printSection("SOURCE", sourceBody);

  printSection("COMMANDS", [
    `${chalk.magenta("c")} continue   ${chalk.magenta("si")} step into   ${chalk.magenta("so")} step over   ${chalk.magenta("su")} step out`,
    `${chalk.magenta("ba")} add breakpoint   ${chalk.magenta("br")} remove breakpoint   ${chalk.magenta("f")} files`,
    `${chalk.magenta("s")} refresh status   ${chalk.magenta("bench")} protocol benchmark   ${chalk.magenta("q")} quit`,
  ]);
}

async function ask(promptText: string, defaultValue = ""): Promise<string> {
  const promptLabel = defaultValue ? `${promptText} [${defaultValue}]` : promptText;
  const value = prompt(chalk.cyan(promptLabel));
  if (value == null || value.trim() === "") {
    return defaultValue;
  }
  return value.trim();
}

async function writeStdout(text: string): Promise<void> {
  await Deno.stdout.write(encoder.encode(text));
}

function setRawMode(enabled: boolean): void {
  try {
    Deno.stdin.setRaw(enabled);
  } catch {
    // Non-interactive environments can ignore raw mode.
  }
}

async function withRawMode<T>(fn: () => Promise<T>): Promise<T> {
  setRawMode(true);
  try {
    return await fn();
  } finally {
    setRawMode(false);
  }
}

async function readKey(): Promise<KeyInput> {
  const buffer = new Uint8Array(16);
  const count = await Deno.stdin.read(buffer);
  if (count === null || count === 0) {
    return { kind: "escape" };
  }

  const bytes = buffer.subarray(0, count);

  if (pendingEscapeSequence) {
    pendingEscapeSequence = false;
    if (bytes.length >= 2 && bytes[0] === 91) {
      switch (bytes[1]) {
        case 65:
          return { kind: "up" };
        case 66:
          return { kind: "down" };
        case 67:
          return { kind: "right" };
        case 68:
          return { kind: "left" };
        default:
          return { kind: "other" };
      }
    }
    return { kind: "escape" };
  }

  const first = bytes[0];
  if (first === 27) {
    if (bytes.length >= 3 && bytes[1] === 91) {
      switch (bytes[2]) {
        case 65:
          return { kind: "up" };
        case 66:
          return { kind: "down" };
        case 67:
          return { kind: "right" };
        case 68:
          return { kind: "left" };
        default:
          return { kind: "other" };
      }
    }

    // Some terminals split ESC and [A/B/C/D into separate reads.
    if (bytes.length === 1) {
      pendingEscapeSequence = true;
      return { kind: "other" };
    }

    return { kind: "escape" };
  }

  if (first === 13 || first === 10) {
    return { kind: "enter" };
  }

  if (first === 32) {
    return { kind: "space" };
  }

  if (first === 127 || first === 8) {
    return { kind: "backspace" };
  }

  if (first >= 32) {
    return { kind: "char", value: String.fromCharCode(first) };
  }

  return { kind: "other" };
}

async function ensureBridgeClasses(): Promise<void> {
  const classFile = `${REPO_ROOT}/target/classes/dev/javadebugger/bridge/BridgeMain.class`;
  try {
    await Deno.stat(classFile);
    return;
  } catch {
    // Compile if bridge class is not built yet.
  }

  console.log(chalk.yellow("Compiling Java classes for bridge..."));
  const compile = new Deno.Command("mvn", {
    args: ["-q", "-DskipTests", "compile"],
    cwd: REPO_ROOT,
    stdout: "inherit",
    stderr: "inherit",
  });
  const output = await compile.output();
  if (!output.success) {
    throw new Error("Failed to compile Java bridge classes");
  }
}

function parseRecords(value: string): string[][] {
  if (!value) {
    return [];
  }
  return value
    .split(RECORD_SEP)
    .filter((row) => row.length > 0)
    .map((row) => row.split(FIELD_SEP));
}

function parseBreakpoints(records: string): BreakpointRow[] {
  return parseRecords(records).map((fields) => ({
    id: Number(fields[0] ?? "0"),
    enabled: fields[1] === "true",
    line: Number(fields[2] ?? "0"),
    sourcePath: fields[3] ?? "",
  }));
}

function parseSourceLines(records: string): SourceLine[] {
  return parseRecords(records).map((fields) => ({
    line: Number(fields[0] ?? "0"),
    marker: fields[1] ?? "",
    text: fields[2] ?? "",
  }));
}

function parseFiles(records: string): DebugFile[] {
  return parseRecords(records).map((fields) => ({
    sourcePath: fields[0] ?? "",
    className: fields[1] ?? "",
    hasMainMethod: fields[2] === "true",
    executableLines: (fields[3] ?? "")
      .split(",")
      .map((value) => Number(value))
      .filter((value) => Number.isFinite(value) && value > 0),
  }));
}

function parseSnippet(records: string): SnippetLine[] {
  return parseRecords(records).map((fields) => ({
    line: Number(fields[0] ?? "0"),
    text: fields[1] ?? "",
  }));
}

async function fetchFileSnippet(bridge: BridgeClient, file: DebugFile, line: number): Promise<FileSnippet | null> {
  const response = await bridge.request("file-snippet", {
    sourcePath: file.sourcePath,
    line: String(line),
    context: "10",
  });

  if (!response.ok || response.fields.status !== "ok") {
    return null;
  }

  return {
    path: response.fields.path ?? file.sourcePath,
    line: Number(response.fields.line ?? String(line)),
    lines: parseSnippet(response.fields.records ?? ""),
  };
}

function currentBreakpointsForFile(sourcePath: string): Set<number> {
  return new Set(
    state.breakpoints
      .filter((breakpoint) => breakpoint.sourcePath === sourcePath)
      .map((breakpoint) => breakpoint.line),
  );
}

function printFilePicker(files: DebugFile[], selectedIndex: number): void {
  clearScreen();
  console.log(titleBanner());
  console.log(chalk.bold.cyan("Deno TUI + Java JDI engine"));
  console.log(chalk.dim("File picker: choose a file, then toggle breakpoints on executable lines"));
  console.log();

  printSection("FILE PICKER", [
    `Choose a file number, or type ${chalk.magenta("q")} to exit.`,
    ...files.slice(0, 20).map((file, index) => {
      const marker = index === selectedIndex ? chalk.redBright("➜") : " ";
      const mainFlag = file.hasMainMethod ? chalk.greenBright("main") : chalk.gray("    ");
      return `${marker} ${String(index + 1).padStart(2, " ")}. ${chalk.cyanBright(file.sourcePath)} ${mainFlag}`;
    }),
  ]);
}

function printFileBreakpointView(file: DebugFile, breakpoints: Set<number>, cursor: number, snippet: FileSnippet | null): void {
  clearScreen();
  console.log(titleBanner());
  console.log(chalk.bold.cyan("Deno TUI + Java JDI engine"));
  console.log(chalk.dim("Breakpoint editor: toggle executable lines in the selected file"));
  console.log();

  const header = [
    chalk.cyanBright(file.sourcePath),
    file.className ? chalk.dim(`class: ${file.className}`) : chalk.dim("class: <unknown>"),
    file.hasMainMethod ? chalk.greenBright("contains main()") : chalk.gray("no main() method"),
    chalk.dim("Use ↑/↓ to move, Enter or Space to toggle breakpoint, q/Esc to go back."),
  ];
  printSection("BREAKPOINT FILE", header);

  if (!snippet || snippet.lines.length === 0) {
    printSection("SOURCE", [chalk.yellow("Source preview unavailable for this file."), chalk.gray("You can still toggle executable lines.")]);
    return;
  }

  const executableSet = new Set(file.executableLines);
  const targetLine = file.executableLines[cursor];
  const body = snippet.lines.map((row) => {
    const pointer = row.line === targetLine ? chalk.redBright("➜") : " ";
    const breakpointMark = breakpoints.has(row.line) ? chalk.greenBright("[*]") : chalk.gray("[ ]");
    const executableMark = executableSet.has(row.line) ? chalk.cyanBright("●") : chalk.gray("·");
    return `${pointer} ${breakpointMark} ${executableMark} ${String(row.line).padStart(4, " ")} | ${row.text}`;
  });
  printSection("SOURCE", body);
}

async function readCommandInput(defaultValue: string): Promise<string> {
  return await withRawMode(async () => {
    let buffer = "";
    await writeStdout(chalk.cyan("jdbg-js> "));

    while (true) {
      const key = await readKey();
      switch (key.kind) {
        case "up":
          await writeStdout("\n");
          return "si";
        case "down":
          await writeStdout("\n");
          return "c";
        case "right":
          await writeStdout("\n");
          return "so";
        case "left":
          await writeStdout("\n");
          return "su";
        case "enter":
          await writeStdout("\n");
          return buffer.trim() || defaultValue;
        case "backspace":
          if (buffer.length > 0) {
            buffer = buffer.slice(0, -1);
            await writeStdout("\r\x1b[2K");
            await writeStdout(chalk.cyan("jdbg-js> ") + buffer);
          }
          break;
        case "char":
          if (key.value) {
            buffer += key.value;
            await writeStdout(key.value);
          }
          break;
        default:
          break;
      }
    }
  });
}

async function handleFilesCommand(bridge: BridgeClient): Promise<void> {
  const filesRes = await bridge.request("files", {});
  if (!filesRes.ok) {
    state.activity = filesRes.fields.message || "Failed to list files";
    renderDashboard();
    return;
  }

  const files = parseFiles(filesRes.fields.records ?? "");
  if (files.length === 0) {
    state.activity = "No project source files found under src/main/java for this app.";
    renderDashboard();
    return;
  }

  await withRawMode(async () => {
    let selectedIndex = 0;
    while (true) {
      printFilePicker(files, selectedIndex);
      const key = await readKey();
      switch (key.kind) {
        case "up":
          selectedIndex = Math.max(0, selectedIndex - 1);
          break;
        case "down":
          selectedIndex = Math.min(files.length - 1, selectedIndex + 1);
          break;
        case "enter":
          await editSelectedFileBreakpoints(bridge, files[selectedIndex]);
          break;
        case "escape":
          state.activity = "Exited file picker.";
          return;
        case "char":
          if (key.value === "q") {
            state.activity = "Exited file picker.";
            return;
          }
          if (key.value === "j") {
            selectedIndex = Math.min(files.length - 1, selectedIndex + 1);
          } else if (key.value === "k") {
            selectedIndex = Math.max(0, selectedIndex - 1);
          }
          break;
        default:
          break;
      }
    }
  });
}

async function editSelectedFileBreakpoints(bridge: BridgeClient, file: DebugFile): Promise<void> {
  if (file.executableLines.length === 0) {
    state.activity = `No executable lines reported for ${file.sourcePath}`;
    return;
  }

  await withRawMode(async () => {
    let cursor = 0;
    while (true) {
      await refreshState(bridge);
      const current = currentBreakpointsForFile(file.sourcePath);
      const focusedLine = file.executableLines[cursor];
      const snippet = await fetchFileSnippet(bridge, file, focusedLine);
      printFileBreakpointView(file, current, cursor, snippet);

      const key = await readKey();
      switch (key.kind) {
        case "up":
          cursor = Math.max(0, cursor - 1);
          break;
        case "down":
          cursor = Math.min(file.executableLines.length - 1, cursor + 1);
          break;
        case "left":
          cursor = Math.max(0, cursor - 10);
          break;
        case "right":
          cursor = Math.min(file.executableLines.length - 1, cursor + 10);
          break;
        case "enter":
        case "space": {
          const line = file.executableLines[cursor];
          const existing = state.breakpoints.find((breakpoint) => breakpoint.sourcePath === file.sourcePath && breakpoint.line === line);
          if (existing) {
            const result = await bridge.request("bp-remove", { id: String(existing.id) });
            state.activity = result.ok
              ? `Removed breakpoint ${existing.id} from ${file.sourcePath}:${line}`
              : (result.fields.message || `Failed to remove breakpoint ${existing.id}`);
          } else {
            const result = await bridge.request("bp-add", { sourcePath: file.sourcePath, line: String(line) });
            state.activity = result.ok
              ? `Added breakpoint ${result.fields.id} at ${file.sourcePath}:${line}`
              : (result.fields.message || `Failed to add breakpoint at ${file.sourcePath}:${line}`);
          }
          break;
        }
        case "escape":
          state.activity = `Exited ${file.sourcePath}`;
          return;
        case "char":
          if (key.value === "q") {
            state.activity = `Exited ${file.sourcePath}`;
            return;
          }
          if (key.value === "j") {
            cursor = Math.min(file.executableLines.length - 1, cursor + 1);
          } else if (key.value === "k") {
            cursor = Math.max(0, cursor - 1);
          }
          break;
        default:
          break;
      }
    }
  });
}

function summarizeEvent(event: BridgeEvent): string {
  const summary = event.fields.summary ?? "Debugger event";
  return `${event.name}: ${summary}`;
}

async function refreshState(bridge: BridgeClient): Promise<void> {
  const [statusRes, breakpointsRes, sourceRes] = await Promise.all([
    bridge.request("status", {}),
    bridge.request("breakpoints", {}),
    bridge.request("source", {}),
  ]);

  if (!statusRes.ok) {
    state.status = "Status request failed";
  } else {
    state.status = statusRes.fields.summary || statusRes.fields.state || "Unknown";
  }

  if (breakpointsRes.ok) {
    state.breakpoints = parseBreakpoints(breakpointsRes.fields.records ?? "");
  }

  if (sourceRes.ok && sourceRes.fields.status === "ok") {
    state.sourcePath = sourceRes.fields.path ?? "";
    state.sourceLine = Number(sourceRes.fields.line ?? "-1");
    state.sourceLines = parseSourceLines(sourceRes.fields.records ?? "");
  } else {
    state.sourcePath = "";
    state.sourceLine = -1;
    state.sourceLines = [];
  }
}

async function waitForStop(bridge: BridgeClient, timeoutMs: number): Promise<void> {
  const waitRes = await bridge.request("wait", { timeoutMs: String(timeoutMs) });
  if (!waitRes.ok) {
    state.activity = waitRes.fields.message ?? "Wait failed";
    return;
  }

  if (waitRes.fields.event === "timeout") {
    state.activity = "No stop event yet. Target is still running.";
    return;
  }

  state.activity = waitRes.fields.summary ?? "Stopped";
}

function encodeList(values: string[]): string {
  return values.join(RECORD_SEP);
}

async function runBenchmark(bridge: BridgeClient): Promise<void> {
  const iterations = 200;
  const started = performance.now();
  for (let i = 0; i < iterations; i += 1) {
    const response = await bridge.request("ping", {});
    if (!response.ok) {
      throw new Error(response.fields.message || "Ping failed");
    }
  }
  const elapsed = performance.now() - started;
  const avg = elapsed / iterations;
  state.activity = `Bridge ping benchmark: ${iterations} requests in ${elapsed.toFixed(1)} ms (${avg.toFixed(2)} ms avg)`;
}

async function autoResumeFromLaunchStartIfNeeded(bridge: BridgeClient, mode: AppMode): Promise<void> {
  if (mode.kind !== "launch") {
    return;
  }

  const status = await bridge.request("status", {});
  if (!status.ok) {
    return;
  }

  if (status.fields.state !== "paused" || status.fields.kind !== "start") {
    return;
  }

  const resumed = await bridge.request("continue", {});
  if (!resumed.ok) {
    state.activity = resumed.fields.message || "Failed to auto-continue from startup pause.";
    return;
  }

  const waitRes = await bridge.request("wait", { timeoutMs: "20000" });
  if (!waitRes.ok) {
    state.activity = waitRes.fields.message || "Auto-continue requested, but waiting for first stop failed.";
    return;
  }

  if (waitRes.fields.event === "timeout") {
    state.activity = "Auto-started execution; waiting for first breakpoint hit.";
    return;
  }

  state.activity = waitRes.fields.summary || "Auto-started and stopped on first debugger event.";
}

async function interactiveLoop(bridge: BridgeClient): Promise<void> {
  while (true) {
    await refreshState(bridge);
    renderDashboard();

    const input = (await readCommandInput("s")).trim();
    if (!input) {
      continue;
    }

    if (input === "q" || input === "quit" || input === "exit") {
      return;
    }

    if (input === "s" || input === "status") {
      state.activity = "Status refreshed.";
      continue;
    }

    if (input === "c" || input === "continue") {
      const result = await bridge.request("continue", {});
      state.activity = result.ok ? "Execution resumed." : (result.fields.message || "Continue failed");
      await waitForStop(bridge, 20000);
      continue;
    }

    if (input === "si" || input === "step into") {
      const result = await bridge.request("step", { direction: "into" });
      state.activity = result.ok ? "Step into requested." : (result.fields.message || "Step into failed");
      await waitForStop(bridge, 20000);
      continue;
    }

    if (input === "so" || input === "step over") {
      const result = await bridge.request("step", { direction: "over" });
      state.activity = result.ok ? "Step over requested." : (result.fields.message || "Step over failed");
      await waitForStop(bridge, 20000);
      continue;
    }

    if (input === "su" || input === "step out") {
      const result = await bridge.request("step", { direction: "out" });
      state.activity = result.ok ? "Step out requested." : (result.fields.message || "Step out failed");
      await waitForStop(bridge, 20000);
      continue;
    }

    if (input === "ba" || input === "bp add") {
      const sourcePath = await ask("source path", "");
      const line = await ask("line", "");
      const result = await bridge.request("bp-add", { sourcePath, line });
      state.activity = result.ok
        ? `Added breakpoint ${result.fields.id} at ${result.fields.sourcePath}:${result.fields.line}`
        : (result.fields.message || "Add breakpoint failed");
      continue;
    }

    if (input === "br" || input === "bp remove") {
      const id = await ask("breakpoint id", "");
      const result = await bridge.request("bp-remove", { id });
      state.activity = result.ok
        ? (result.fields.changed === "true" ? `Removed breakpoint ${id}` : `No breakpoint ${id} found`)
        : (result.fields.message || "Remove breakpoint failed");
      continue;
    }

    if (input === "f" || input === "files") {
      await handleFilesCommand(bridge);
      continue;
    }

    if (input === "bench") {
      await runBenchmark(bridge);
      continue;
    }

    state.activity = `Unknown command: ${input}`;
  }
}

async function runDebugger(mode: AppMode): Promise<void> {
  await ensureBridgeClasses();

  const bridgeClasspath = `${REPO_ROOT}/target/classes`;
  const bridge = new BridgeClient(bridgeClasspath);
  bridge.onEvent((event) => {
    if (event.name === "stop") {
      state.activity = summarizeEvent(event);
    }
  });

  await bridge.start();
  try {
    if (mode.kind === "attach") {
      const response = await bridge.request("attach", {
        host: mode.host,
        port: String(mode.port),
        sourceRoots: encodeList(mode.sourceRoots),
      });
      if (!response.ok) {
        throw new Error(response.fields.message || "attach failed");
      }
      state.activity = response.fields.message || `Attached to ${mode.host}:${mode.port}`;
    } else {
      const response = await bridge.request("launch", {
        mainClass: mode.mainClass,
        classpath: mode.classpath,
        programArgs: encodeList(mode.programArgs),
      });
      if (!response.ok) {
        throw new Error(response.fields.message || "launch failed");
      }
      state.activity = response.fields.message || `Launched ${mode.mainClass}`;
    }

    await autoResumeFromLaunchStartIfNeeded(bridge, mode);

    await interactiveLoop(bridge);
  } finally {
    await bridge.stop();
  }
}

async function runBridgeBenchOnly(): Promise<void> {
  await ensureBridgeClasses();
  const bridge = new BridgeClient(`${REPO_ROOT}/target/classes`);
  await bridge.start();
  try {
    const started = performance.now();
    const iterations = 500;
    for (let i = 0; i < iterations; i += 1) {
      const response = await bridge.request("ping", {});
      if (!response.ok) {
        throw new Error(response.fields.message || "Bridge ping failed");
      }
    }
    const elapsed = performance.now() - started;
    console.log(`Bridge protocol benchmark: ${iterations} pings in ${elapsed.toFixed(2)} ms (${(elapsed / iterations).toFixed(3)} ms avg)`);
  } finally {
    await bridge.stop();
  }
}

function parseArgs(argv: string[]): { command: string; flags: Map<string, string[]> } {
  const command = argv[0] ?? "";
  const flags = new Map<string, string[]>();

  let i = 1;
  while (i < argv.length) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      i += 1;
      continue;
    }
    const key = token.slice(2);
    const value = argv[i + 1] ?? "";
    if (!flags.has(key)) {
      flags.set(key, []);
    }
    flags.get(key)!.push(value);
    i += 2;
  }

  return { command, flags };
}

function getFlag(flags: Map<string, string[]>, key: string, defaultValue = ""): string {
  const values = flags.get(key);
  if (!values || values.length === 0) {
    return defaultValue;
  }
  return values[values.length - 1];
}

function getFlags(flags: Map<string, string[]>, key: string): string[] {
  return flags.get(key) ?? [];
}

function printUsage(): void {
  console.log("Usage:");
  console.log("  deno task start attach --host 127.0.0.1 --port 5005 [--source-root /path]");
  console.log("  deno task start launch --main-class pkg.Main --classpath ../target/classes [--arg value]");
  console.log("  deno task bench");
}

const parsed = parseArgs(Deno.args);

if (parsed.command === "bench") {
  await runBridgeBenchOnly();
} else if (parsed.command === "attach") {
  const host = getFlag(parsed.flags, "host", "127.0.0.1");
  const port = Number(getFlag(parsed.flags, "port", "5005"));
  const sourceRoots = getFlags(parsed.flags, "source-root");
  await runDebugger({ kind: "attach", host, port, sourceRoots });
} else if (parsed.command === "launch") {
  const mainClass = getFlag(parsed.flags, "main-class", "");
  const classpath = getFlag(parsed.flags, "classpath", "");
  if (!mainClass || !classpath) {
    printUsage();
    Deno.exit(2);
  }
  const programArgs = getFlags(parsed.flags, "arg");
  await runDebugger({ kind: "launch", mainClass, classpath, programArgs });
} else {
  printUsage();
  Deno.exit(2);
}
