# Java Debugger Feature Baseline (LLM Sync Doc)

This document is the implementation baseline for future feature work across both CLIs:

- Java CLI in src/main/java/dev/javadebugger/cli/Main.java
- JS Ink CLI in deno-tui/src/main.tsx (via bridge_client.ts and BridgeMain.java)

Use this as the source of truth for behavior-preserving changes.

## Goals

- Keep debugger behavior consistent across Java and JS front ends.
- Preserve protocol compatibility between bridge client and bridge server.
- Make feature planning explicit about parity gaps (intentional and unintentional).

## Runtime Architecture

- Debug engine: DebuggerSession (JDI) in src/main/java/dev/javadebugger/core/DebuggerSession.java
- Native CLI shell: Java Main class in src/main/java/dev/javadebugger/cli/Main.java
- Bridge server: src/main/java/dev/javadebugger/bridge/BridgeMain.java
- JS UI client: deno-tui/src/main.tsx with deno-tui/src/bridge_client.ts

The Java CLI and JS Ink CLI must both be treated as front ends over the same debugger semantics.

## Shared Functional Requirements (Both CLIs)

1. Session modes
- Support attach mode and launch mode.
- Default attach target is 127.0.0.1:5005 when not explicitly overridden.

2. Core execution controls
- Continue/resume execution.
- Step into, step over, and step out.
- Report current stop/status.

3. Breakpoint fundamentals
- Add line breakpoints by source path + line.
- Remove breakpoints by breakpoint id.
- Show breakpoint list including enabled state and location.
- Avoid duplicate breakpoint creation for same logical source location.

4. Source context
- Render source context around current stop when local source is available.
- Return a clear fallback message when source cannot be resolved.

5. File-driven breakpoint workflows
- Provide a file list workflow and line-level breakpoint toggling.

6. Safety behavior
- Graceful handling when stepping while not paused.
- Graceful handling when target has already terminated.

## Java CLI Requirements

Source: src/main/java/dev/javadebugger/cli/Main.java

1. Command surface
- continue/start
- step into|over|out
- status
- breaks
- clear <id>
- files
- manage
- state clear
- restart
- quit/exit
- help

2. Interactive UX
- Arrow key shortcuts at prompt:
  - up: step into
  - down: continue
  - right: step over
  - left: step out
- Enter on empty prompt repeats last continue/step command.
- Single-letter command expansion (for example c, s, l, f, m, r, q, h).

3. Async stop pickup while idle
- While waiting for user input, async breakpoint hits must be surfaced without blocking prompt flow.

4. Breakpoint management breadth
- Dedicated manager supports toggle enable/disable, delete, enable all, disable all, delete all.

5. Persistence semantics
- Persist breakpoints to ~/.java-debugger/breakpoints.dbg.
- Restore persisted breakpoints on startup/restart.
- Support state clear for persisted state removal.

6. Restart semantics
- restart restarts session and reapplies in-memory/persisted breakpoints.

## JS Ink CLI Requirements

Source: deno-tui/src/main.tsx

1. Rendering/runtime
- Use Ink (React terminal UI) for dashboard and picker/editor views.
- Use BridgeClient for all debugger operations over BridgeMain protocol.

2. Command surface currently implemented
- continue (c)
- step into (si)
- step over (so)
- step out (su)
- status (s)
- bp add flow (ba)
- bp remove flow (br)
- files (f)
- bench
- quit/exit (q)

3. Keyboard controls
- Arrow mappings in dashboard:
  - up: step into
  - down: continue
  - right: step over
  - left: step out
- In file picker/editor: arrows + Enter/Space for navigation and toggle.

4. Launch mode startup behavior
- If launch starts paused at VM start, auto-continue and wait for first meaningful stop/timeout message.

5. Scope note
- JS CLI currently does not implement Java CLI-only commands such as restart, state clear, or full manage-all breakpoint operations.

## Bridge Protocol Requirements

Source: src/main/java/dev/javadebugger/bridge/BridgeMain.java and deno-tui/src/bridge_client.ts

1. Transport and framing
- Line-oriented request/response protocol over stdio.
- Record separator: U+001E
- Field separator: U+001F
- Percent-encoding for token fields.

2. Command contract (must remain stable unless versioned)
- ping
- attach
- launch
- status
- continue
- step
- wait
- breakpoints
- bp-add
- bp-remove
- bp-enable
- bp-disable
- files
- file-snippet
- source
- quit

3. Response semantics
- Every request has deterministic ok/error response with fields map.
- Async stop events are emitted separately as event messages and consumed by JS client event handler.
- Bridge client request timeout currently defaults to 30 seconds.

4. Compatibility requirement
- Any bridge field rename/removal/addition must be treated as protocol change and coordinated across BridgeMain and bridge_client.ts and JS UI parsing logic.

## Attach/Launch Mode Requirements

1. Attach mode
- Do not broad-scan filesystem.
- Source root defaults: ./src/main/java and ./src/test/java from current working directory when none are provided.
- Allow multiple explicit source roots.

2. Launch mode
- Start target with suspend=true semantics through JDI launch connector.
- Surface initial stop and support immediate stepping/continue.

## Stepping and Stop Semantics

Source: src/main/java/dev/javadebugger/core/DebuggerSession.java

1. Stepping preconditions
- Step requires suspended thread/current stop; otherwise user-facing non-crashing feedback.

2. End-of-program edge behavior
- Non-source terminal step events (line <= 0) can occur; stepping flow should auto-advance/handle gracefully until visible stop or termination.

3. Breakpoint resume behavior
- Continuing from a breakpoint should avoid immediate re-hit loops at the same location.

## Source/File Discovery Requirements

1. File list relevance filtering
- Exclude obvious dependency/runtime/framework classes from file picker output.

2. Source resolution fallback
- If source path is unavailable or unmapped locally, provide explicit message instead of failing silently.

3. Snippet context windows
- Java CLI and bridge source/snippet views should show nearby context lines around current line.

## Persistence Requirements

Source: src/main/java/dev/javadebugger/cli/BreakpointPersistence.java

1. Storage format
- Versioned file format with v1 header.
- Tab-delimited escaped fields: source path, line, enabled.

2. Dedupe policy
- Dedupe by sourcePath:line during load/save.

3. Error handling
- Load errors should warn and fail soft to empty state.
- Save errors should not crash debugger command loop.

## Verified Behavior Evidence

Source: src/test/java/dev/javadebugger/core/DebuggerSessionTest.java

- Launch sample app under debugger.
- Hit breakpoint at selected source line.
- Step into, step over, step out.
- Resume to termination.
- Breakpoint list ordering by id.

## Current Parity Matrix

- Shared now
  - Attach and launch support
  - Continue and step controls
  - Breakpoint add/remove
  - File picker + line toggling
  - Status and source context reporting

- Java-only now
  - Breakpoint persistence and state clear command
  - restart command
  - Full breakpoint manager operations (enable/disable all, delete all)
  - Async stop pickup at shell prompt

- JS-only now
  - Ink dashboard rendering and richer view composition
  - Built-in protocol benchmark command in UI flow

## Change Acceptance Checklist For New Features

Any feature proposal touching debugger behavior should answer:

1. Does it change core engine semantics in DebuggerSession?
2. Does it require protocol changes in BridgeMain/bridge_client.ts?
3. Does it apply to both CLIs or only one? Is divergence intentional?
4. Does it preserve attach/launch defaults and stepping guardrails?
5. Does it affect persistence expectations?
6. Were required validations run?

Required validation by area:

- Java core/CLI changes: mvn test
- JS Ink changes: cd deno-tui && deno check src/main.tsx
- Bridge-related changes: cd deno-tui && deno task bench

## Recommended Update Policy

- Update this document whenever command surface, protocol fields, mode behavior, or persistence semantics change.
- Treat this file and AGENTS.md as the minimum context future LLMs should read before implementing debugger features.