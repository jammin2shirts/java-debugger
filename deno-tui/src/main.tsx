/** @jsx React.createElement */
/** @jsxFrag React.Fragment */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "npm:react@18.3.1";
import { Box, render, Text, useApp, useInput } from "npm:ink@5.2.1";
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

type DashboardState = {
  status: string;
  activity: string;
  breakpoints: BreakpointRow[];
  sourcePath: string;
  sourceLine: number;
  sourceLines: SourceLine[];
};

type UiMode = "dashboard" | "file-picker" | "file-editor";
type PromptMode = "bp-add-source" | "bp-add-line" | "bp-remove-id";

const THIS_FILE = new URL(import.meta.url);
const REPO_ROOT = new URL("../..", THIS_FILE).pathname;

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

function summarizeEvent(event: BridgeEvent): string {
  const summary = event.fields.summary ?? "Debugger event";
  return `${event.name}: ${summary}`;
}

function encodeList(values: string[]): string {
  return values.join(RECORD_SEP);
}

async function ensureBridgeClasses(): Promise<void> {
  const classFile = `${REPO_ROOT}/target/classes/dev/javadebugger/bridge/BridgeMain.class`;
  try {
    await Deno.stat(classFile);
    return;
  } catch {
    // Compile if bridge class is not built yet.
  }

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

async function waitForStop(bridge: BridgeClient, timeoutMs: number): Promise<string> {
  const waitRes = await bridge.request("wait", { timeoutMs: String(timeoutMs) });
  if (!waitRes.ok) {
    return waitRes.fields.message ?? "Wait failed";
  }

  if (waitRes.fields.event === "timeout") {
    return "No stop event yet. Target is still running.";
  }

  return waitRes.fields.summary ?? "Stopped";
}

async function runBenchmark(bridge: BridgeClient): Promise<string> {
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
  return `Bridge ping benchmark: ${iterations} requests in ${elapsed.toFixed(1)} ms (${avg.toFixed(2)} ms avg)`;
}

async function autoResumeFromLaunchStartIfNeeded(bridge: BridgeClient, mode: AppMode): Promise<string | null> {
  if (mode.kind !== "launch") {
    return null;
  }

  const status = await bridge.request("status", {});
  if (!status.ok) {
    return null;
  }

  if (status.fields.state !== "paused" || status.fields.kind !== "start") {
    return null;
  }

  const resumed = await bridge.request("continue", {});
  if (!resumed.ok) {
    return resumed.fields.message || "Failed to auto-continue from startup pause.";
  }

  const waitRes = await bridge.request("wait", { timeoutMs: "20000" });
  if (!waitRes.ok) {
    return waitRes.fields.message || "Auto-continue requested, but waiting for first stop failed.";
  }

  if (waitRes.fields.event === "timeout") {
    return "Auto-started execution; waiting for first breakpoint hit.";
  }

  return waitRes.fields.summary || "Auto-started and stopped on first debugger event.";
}

function currentBreakpointsForFile(sourcePath: string, breakpoints: BreakpointRow[]): Set<number> {
  return new Set(
    breakpoints
      .filter((breakpoint) => breakpoint.sourcePath === sourcePath)
      .map((breakpoint) => breakpoint.line),
  );
}

function Section(props: { title: string; lines: string[] }): React.JSX.Element {
  const content = props.lines.join("\n");
  return (
    <Box borderStyle="round" borderColor="cyan" flexDirection="column" paddingX={1} marginBottom={1}>
      <Text color="cyanBright">{props.title}</Text>
      {props.lines.length === 0 ? <Text color="gray">&lt;empty&gt;</Text> : null}
      {props.lines.length > 0 ? <Text>{content}</Text> : null}
    </Box>
  );
}

function DashboardView(props: {
  state: DashboardState;
  commandBuffer: string;
  busy: boolean;
  initialized: boolean;
  promptMode: PromptMode | null;
  promptBuffer: string;
}): React.JSX.Element {
  const breakpointsBody = props.state.breakpoints.length === 0
    ? ["<none>"]
    : props.state.breakpoints.slice(0, 12).map((bp) => {
      const enabled = bp.enabled ? "enabled " : "disabled";
      return `${String(bp.id).padStart(3, " ")} [${enabled}] ${bp.sourcePath}:${bp.line}`;
    });

  const sourceBody = props.state.sourcePath
    ? [
      `${props.state.sourcePath}:${props.state.sourceLine}`,
      ...props.state.sourceLines.map((row) => {
        const marker = row.marker === "->" ? "->" : "  ";
        return `${marker} ${String(row.line).padStart(4, " ")} | ${row.text}`;
      }),
    ]
    : ["No source lines available"];

  let promptLabel = "jdbg-ink>";
  if (props.promptMode === "bp-add-source") {
    promptLabel = "source path>";
  } else if (props.promptMode === "bp-add-line") {
    promptLabel = "line>";
  } else if (props.promptMode === "bp-remove-id") {
    promptLabel = "breakpoint id>";
  }

  return (
    <Box flexDirection="column">
      <Text color="greenBright">JDBG Ink CLI</Text>
      <Text color="gray">Java JDI engine, Ink-rendered JS terminal UI</Text>
      <Text>{""}</Text>

      <Section title="STATUS" lines={[props.state.status]} />
      <Section title="ACTIVITY" lines={[props.state.activity || "No activity yet"]} />
      <Section title="BREAKPOINTS" lines={breakpointsBody} />
      <Section title="SOURCE" lines={sourceBody} />
      <Section
        title="COMMANDS"
        lines={[
          "c continue   si step into   so step over   su step out",
          "ba add breakpoint   br remove breakpoint   f files",
          "s refresh status   bench protocol benchmark   q quit",
          "Arrow keys: down continue, up step into, right step over, left step out",
        ]}
      />

      <Box>
        <Text color="cyan">{promptLabel}</Text>
        <Text> {props.promptBuffer || props.commandBuffer}</Text>
        <Text color="gray">{props.busy ? "  [working]" : props.initialized ? "" : "  [starting]"}</Text>
      </Box>
    </Box>
  );
}

function FilePickerView(props: { files: DebugFile[]; selectedIndex: number }): React.JSX.Element {
  const lines = props.files.slice(0, 20).map((file, index) => {
    const marker = index === props.selectedIndex ? ">" : " ";
    const mainFlag = file.hasMainMethod ? "main" : "    ";
    return `${marker} ${String(index + 1).padStart(2, " ")}. ${file.sourcePath} ${mainFlag}`;
  });

  return (
    <Box flexDirection="column">
      <Text color="greenBright">JDBG Ink CLI</Text>
      <Text color="gray">File picker: choose a file, Enter to open, q/Esc to close</Text>
      <Text>{""}</Text>
      <Section title="FILES" lines={lines} />
    </Box>
  );
}

function FileEditorView(props: {
  file: DebugFile;
  snippet: FileSnippet | null;
  cursor: number;
  breakpoints: Set<number>;
}): React.JSX.Element {
  const focusedLine = props.file.executableLines[props.cursor] ?? -1;
  const executableSet = new Set(props.file.executableLines);

  const header = [
    props.file.sourcePath,
    props.file.className ? `class: ${props.file.className}` : "class: <unknown>",
    props.file.hasMainMethod ? "contains main()" : "no main() method",
    "Use arrows to move and Enter/Space to toggle; q/Esc to return.",
  ];

  const sourceLines = props.snippet?.lines?.length
    ? props.snippet.lines.map((row) => {
      const pointer = row.line === focusedLine ? ">" : " ";
      const breakpointMark = props.breakpoints.has(row.line) ? "[*]" : "[ ]";
      const executableMark = executableSet.has(row.line) ? "o" : ".";
      return `${pointer} ${breakpointMark} ${executableMark} ${String(row.line).padStart(4, " ")} | ${row.text}`;
    })
    : ["Source preview unavailable for this file.", "You can still toggle executable lines."];

  return (
    <Box flexDirection="column">
      <Text color="greenBright">JDBG Ink CLI</Text>
      <Text color="gray">Breakpoint editor</Text>
      <Text>{""}</Text>
      <Section title="FILE" lines={header} />
      <Section title="SOURCE" lines={sourceLines} />
    </Box>
  );
}

function App(props: { mode: AppMode }): React.JSX.Element {
  const { exit } = useApp();

  const [state, setState] = useState<DashboardState>({
    status: "Initializing...",
    activity: "",
    breakpoints: [],
    sourcePath: "",
    sourceLine: -1,
    sourceLines: [],
  });

  const [initialized, setInitialized] = useState(false);
  const [busy, setBusy] = useState(false);
  const [commandBuffer, setCommandBuffer] = useState("");
  const [lastCommand, setLastCommand] = useState("s");
  const [uiMode, setUiMode] = useState<UiMode>("dashboard");

  const [files, setFiles] = useState<DebugFile[]>([]);
  const [selectedFileIndex, setSelectedFileIndex] = useState(0);
  const [fileCursor, setFileCursor] = useState(0);
  const [fileSnippet, setFileSnippet] = useState<FileSnippet | null>(null);

  const [promptMode, setPromptMode] = useState<PromptMode | null>(null);
  const [promptBuffer, setPromptBuffer] = useState("");
  const [pendingBpSource, setPendingBpSource] = useState("");

  const bridgeRef = useRef<BridgeClient | null>(null);
  const mountedRef = useRef(true);

  const setActivity = useCallback((activity: string): void => {
    setState((prev: DashboardState) => ({ ...prev, activity }));
  }, []);

  const refreshState = useCallback(async (): Promise<void> => {
    const bridge = bridgeRef.current;
    if (!bridge) {
      return;
    }

    const [statusRes, breakpointsRes, sourceRes] = await Promise.all([
      bridge.request("status", {}),
      bridge.request("breakpoints", {}),
      bridge.request("source", {}),
    ]);

    setState((prev: DashboardState) => {
      const next: DashboardState = {
        ...prev,
        status: statusRes.ok ? (statusRes.fields.summary || statusRes.fields.state || "Unknown") : "Status request failed",
        breakpoints: breakpointsRes.ok ? parseBreakpoints(breakpointsRes.fields.records ?? "") : prev.breakpoints,
        sourcePath: "",
        sourceLine: -1,
        sourceLines: [],
      };

      if (sourceRes.ok && sourceRes.fields.status === "ok") {
        next.sourcePath = sourceRes.fields.path ?? "";
        next.sourceLine = Number(sourceRes.fields.line ?? "-1");
        next.sourceLines = parseSourceLines(sourceRes.fields.records ?? "");
      }

      return next;
    });
  }, []);

  const runAction = useCallback(async (fn: () => Promise<void>): Promise<void> => {
    if (busy) {
      return;
    }

    setBusy(true);
    try {
      await fn();
      await refreshState();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setActivity(message);
    } finally {
      setBusy(false);
    }
  }, [busy, refreshState, setActivity]);

  const quitApp = useCallback(async (): Promise<void> => {
    const bridge = bridgeRef.current;
    bridgeRef.current = null;
    if (bridge) {
      await bridge.stop().catch(() => undefined);
    }
    exit();
  }, [exit]);

  const executeCommand = useCallback(async (rawInput: string): Promise<void> => {
    const input = rawInput.trim();
    const bridge = bridgeRef.current;
    if (!bridge) {
      return;
    }

    if (!input) {
      return;
    }

    setLastCommand(input);

    if (input === "q" || input === "quit" || input === "exit") {
      await quitApp();
      return;
    }

    if (input === "s" || input === "status") {
      setActivity("Status refreshed.");
      await refreshState();
      return;
    }

    if (input === "c" || input === "continue") {
      await runAction(async () => {
        const result = await bridge.request("continue", {});
        setActivity(result.ok ? "Execution resumed." : (result.fields.message || "Continue failed"));
        setActivity(await waitForStop(bridge, 20000));
      });
      return;
    }

    if (input === "si" || input === "step into") {
      await runAction(async () => {
        const result = await bridge.request("step", { direction: "into" });
        setActivity(result.ok ? "Step into requested." : (result.fields.message || "Step into failed"));
        setActivity(await waitForStop(bridge, 20000));
      });
      return;
    }

    if (input === "so" || input === "step over") {
      await runAction(async () => {
        const result = await bridge.request("step", { direction: "over" });
        setActivity(result.ok ? "Step over requested." : (result.fields.message || "Step over failed"));
        setActivity(await waitForStop(bridge, 20000));
      });
      return;
    }

    if (input === "su" || input === "step out") {
      await runAction(async () => {
        const result = await bridge.request("step", { direction: "out" });
        setActivity(result.ok ? "Step out requested." : (result.fields.message || "Step out failed"));
        setActivity(await waitForStop(bridge, 20000));
      });
      return;
    }

    if (input === "ba" || input === "bp add") {
      setPromptMode("bp-add-source");
      setPromptBuffer("");
      return;
    }

    if (input === "br" || input === "bp remove") {
      setPromptMode("bp-remove-id");
      setPromptBuffer("");
      return;
    }

    if (input === "f" || input === "files") {
      await runAction(async () => {
        const filesRes = await bridge.request("files", {});
        if (!filesRes.ok) {
          setActivity(filesRes.fields.message || "Failed to list files");
          return;
        }

        const nextFiles = parseFiles(filesRes.fields.records ?? "");
        if (nextFiles.length === 0) {
          setActivity("No project source files found under src/main/java for this app.");
          return;
        }

        setFiles(nextFiles);
        setSelectedFileIndex(0);
        setFileCursor(0);
        setUiMode("file-picker");
      });
      return;
    }

    if (input === "bench") {
      await runAction(async () => {
        setActivity(await runBenchmark(bridge));
      });
      return;
    }

    setActivity(`Unknown command: ${input}`);
  }, [quitApp, refreshState, runAction, setActivity]);

  useEffect(() => {
    mountedRef.current = true;

    (async () => {
      try {
        await ensureBridgeClasses();

        const bridgeClasspath = `${REPO_ROOT}/target/classes`;
        const bridge = new BridgeClient(bridgeClasspath);
        bridge.onEvent((event) => {
          if (!mountedRef.current) {
            return;
          }
          if (event.name === "stop") {
            setActivity(summarizeEvent(event));
          }
        });

        await bridge.start();
        bridgeRef.current = bridge;

        if (props.mode.kind === "attach") {
          const response = await bridge.request("attach", {
            host: props.mode.host,
            port: String(props.mode.port),
            sourceRoots: encodeList(props.mode.sourceRoots),
          });
          if (!response.ok) {
            throw new Error(response.fields.message || "attach failed");
          }
          setActivity(response.fields.message || `Attached to ${props.mode.host}:${props.mode.port}`);
        } else {
          const response = await bridge.request("launch", {
            mainClass: props.mode.mainClass,
            classpath: props.mode.classpath,
            programArgs: encodeList(props.mode.programArgs),
          });
          if (!response.ok) {
            throw new Error(response.fields.message || "launch failed");
          }
          setActivity(response.fields.message || `Launched ${props.mode.mainClass}`);
        }

        const autoResumeMessage = await autoResumeFromLaunchStartIfNeeded(bridge, props.mode);
        if (autoResumeMessage) {
          setActivity(autoResumeMessage);
        }

        await refreshState();
        if (mountedRef.current) {
          setInitialized(true);
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setActivity(message);
      }
    })();

    return () => {
      mountedRef.current = false;
      const bridge = bridgeRef.current;
      bridgeRef.current = null;
      if (bridge) {
        bridge.stop().catch(() => undefined);
      }
    };
  }, [props.mode, refreshState, setActivity]);

  const selectedFile = useMemo(() => files[selectedFileIndex], [files, selectedFileIndex]);

  useEffect(() => {
    if (uiMode !== "file-editor") {
      return;
    }
    if (!selectedFile || selectedFile.executableLines.length === 0) {
      setFileSnippet(null);
      return;
    }
    const bridge = bridgeRef.current;
    if (!bridge) {
      return;
    }

    const focusedLine = selectedFile.executableLines[fileCursor] ?? selectedFile.executableLines[0];
    let cancelled = false;
    (async () => {
      const snippet = await fetchFileSnippet(bridge, selectedFile, focusedLine).catch(() => null);
      if (!cancelled) {
        setFileSnippet(snippet);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [fileCursor, selectedFile, uiMode]);

  useInput((input, key) => {
    if (key.ctrl && input === "c") {
      runAction(async () => {
        await quitApp();
      });
      return;
    }

    if (!initialized || busy) {
      return;
    }

    if (uiMode === "file-picker") {
      if (key.upArrow) {
        setSelectedFileIndex((prev: number) => Math.max(0, prev - 1));
        return;
      }
      if (key.downArrow) {
        setSelectedFileIndex((prev: number) => Math.min(files.length - 1, prev + 1));
        return;
      }
      if (key.return) {
        const file = files[selectedFileIndex];
        if (!file) {
          return;
        }
        if (file.executableLines.length === 0) {
          setActivity(`No executable lines reported for ${file.sourcePath}`);
          return;
        }
        setFileCursor(0);
        setUiMode("file-editor");
        return;
      }
      if (key.escape || input === "q") {
        setUiMode("dashboard");
        setActivity("Exited file picker.");
        return;
      }
      if (input === "j") {
        setSelectedFileIndex((prev: number) => Math.min(files.length - 1, prev + 1));
        return;
      }
      if (input === "k") {
        setSelectedFileIndex((prev: number) => Math.max(0, prev - 1));
      }
      return;
    }

    if (uiMode === "file-editor") {
      const file = selectedFile;
      if (!file) {
        setUiMode("dashboard");
        return;
      }

      if (key.upArrow) {
        setFileCursor((prev: number) => Math.max(0, prev - 1));
        return;
      }
      if (key.downArrow) {
        setFileCursor((prev: number) => Math.min(file.executableLines.length - 1, prev + 1));
        return;
      }
      if (key.leftArrow) {
        setFileCursor((prev: number) => Math.max(0, prev - 10));
        return;
      }
      if (key.rightArrow) {
        setFileCursor((prev: number) => Math.min(file.executableLines.length - 1, prev + 10));
        return;
      }
      if (key.escape || input === "q") {
        setUiMode("file-picker");
        setActivity(`Exited ${file.sourcePath}`);
        return;
      }
      if (input === "j") {
        setFileCursor((prev: number) => Math.min(file.executableLines.length - 1, prev + 1));
        return;
      }
      if (input === "k") {
        setFileCursor((prev: number) => Math.max(0, prev - 1));
        return;
      }

      if (key.return || input === " ") {
        runAction(async () => {
          const bridge = bridgeRef.current;
          if (!bridge) {
            return;
          }
          const line = file.executableLines[fileCursor];
          const existing = state.breakpoints.find((breakpoint: BreakpointRow) => breakpoint.sourcePath === file.sourcePath && breakpoint.line === line);

          if (existing) {
            const result = await bridge.request("bp-remove", { id: String(existing.id) });
            setActivity(result.ok
              ? `Removed breakpoint ${existing.id} from ${file.sourcePath}:${line}`
              : (result.fields.message || `Failed to remove breakpoint ${existing.id}`));
          } else {
            const result = await bridge.request("bp-add", { sourcePath: file.sourcePath, line: String(line) });
            setActivity(result.ok
              ? `Added breakpoint ${result.fields.id} at ${file.sourcePath}:${line}`
              : (result.fields.message || `Failed to add breakpoint at ${file.sourcePath}:${line}`));
          }
        });
      }
      return;
    }

    if (promptMode) {
      if (key.escape) {
        setPromptMode(null);
        setPromptBuffer("");
        setPendingBpSource("");
        return;
      }

      if (key.backspace || key.delete) {
        setPromptBuffer((prev: string) => prev.slice(0, -1));
        return;
      }

      if (key.return) {
        const value = promptBuffer.trim();
        if (promptMode === "bp-add-source") {
          setPendingBpSource(value);
          setPromptBuffer("");
          setPromptMode("bp-add-line");
          return;
        }

        if (promptMode === "bp-add-line") {
          runAction(async () => {
            const bridge = bridgeRef.current;
            if (!bridge) {
              return;
            }
            const result = await bridge.request("bp-add", { sourcePath: pendingBpSource, line: value });
            setActivity(result.ok
              ? `Added breakpoint ${result.fields.id} at ${result.fields.sourcePath}:${result.fields.line}`
              : (result.fields.message || "Add breakpoint failed"));
          });
          setPromptMode(null);
          setPromptBuffer("");
          setPendingBpSource("");
          return;
        }

        runAction(async () => {
          const bridge = bridgeRef.current;
          if (!bridge) {
            return;
          }
          const result = await bridge.request("bp-remove", { id: value });
          setActivity(result.ok
            ? (result.fields.changed === "true" ? `Removed breakpoint ${value}` : `No breakpoint ${value} found`)
            : (result.fields.message || "Remove breakpoint failed"));
        });
        setPromptMode(null);
        setPromptBuffer("");
        return;
      }

      if (input) {
        setPromptBuffer((prev: string) => prev + input);
      }
      return;
    }

    if (key.upArrow) {
      runAction(async () => {
        await executeCommand("si");
      });
      return;
    }

    if (key.downArrow) {
      runAction(async () => {
        await executeCommand("c");
      });
      return;
    }

    if (key.leftArrow) {
      runAction(async () => {
        await executeCommand("su");
      });
      return;
    }

    if (key.rightArrow) {
      runAction(async () => {
        await executeCommand("so");
      });
      return;
    }

    if (key.backspace || key.delete) {
      setCommandBuffer((prev: string) => prev.slice(0, -1));
      return;
    }

    if (key.return) {
      const command = commandBuffer.trim() || lastCommand;
      setCommandBuffer("");
      runAction(async () => {
        await executeCommand(command);
      });
      return;
    }

    if (input) {
      setCommandBuffer((prev: string) => prev + input);
    }
  });

  if (uiMode === "file-picker") {
    return <FilePickerView files={files} selectedIndex={selectedFileIndex} />;
  }

  if (uiMode === "file-editor" && selectedFile) {
    return (
      <FileEditorView
        file={selectedFile}
        snippet={fileSnippet}
        cursor={fileCursor}
        breakpoints={currentBreakpointsForFile(selectedFile.sourcePath, state.breakpoints)}
      />
    );
  }

  return (
    <DashboardView
      state={state}
      commandBuffer={commandBuffer}
      busy={busy}
      initialized={initialized}
      promptMode={promptMode}
      promptBuffer={promptBuffer}
    />
  );
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
    flags.get(key)?.push(value);
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
  const app = render(<App mode={{ kind: "attach", host, port, sourceRoots }} />);
  await app.waitUntilExit();
} else if (parsed.command === "launch") {
  const mainClass = getFlag(parsed.flags, "main-class", "");
  const classpath = getFlag(parsed.flags, "classpath", "");
  if (!mainClass || !classpath) {
    printUsage();
    Deno.exit(2);
  }
  const programArgs = getFlags(parsed.flags, "arg");
  const app = render(<App mode={{ kind: "launch", mainClass, classpath, programArgs }} />);
  await app.waitUntilExit();
} else {
  printUsage();
  Deno.exit(2);
}