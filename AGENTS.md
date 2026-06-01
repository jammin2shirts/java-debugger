# AGENTS.md

Agent guidance for this repository. Keep changes minimal, preserve current behavior, and link to existing docs instead of duplicating them.

## Scope

- Primary runtime: Java CLI debugger on JDI.
- Secondary runtime: Deno Ink terminal UI that drives the Java bridge process.
- Canonical user documentation: [README.md](README.md).

## Fast Start

Run commands from repo root unless noted.

- Build and test: `mvn test`
- Package (full): `mvn clean package`
- Package (fast): `mvn -DskipTests clean package`
- Install CLI on macOS: `./scripts/install-jdbg.sh`
- Uninstall CLI: `./scripts/uninstall-jdbg.sh`
- Deno TUI attach: `cd deno-tui && deno task start attach --host 127.0.0.1 --port 5005`
- Deno TUI launch: `cd deno-tui && deno task start launch --main-class <CLASS> --classpath <PATHS>`
- Bridge benchmark: `cd deno-tui && deno task bench`

## Project Map

- Java engine and debug control: `src/main/java/dev/javadebugger/core/`
- Java CLI workflow and persistence: `src/main/java/dev/javadebugger/cli/`
- Java bridge protocol endpoint: `src/main/java/dev/javadebugger/bridge/BridgeMain.java`
- Deno Ink UI: `deno-tui/src/main.tsx`
- Deno bridge client: `deno-tui/src/bridge_client.ts`
- Integration-style tests: `src/test/java/dev/javadebugger/core/DebuggerSessionTest.java`
- Sample debuggee: `src/test/java/dev/javadebugger/sample/SampleApp.java`

## Conventions That Matter

- Default attach mode is `127.0.0.1:5005` when no mode flags are provided.
- Attach source roots should be explicit for external apps. Defaults are `./src/main/java` and `./src/test/java` from current working directory.
- Keep stepping UX resilient: stepping before first pause should not throw.
- End-of-program step events may be non-source (`line <= 0`); preserve current auto-advance behavior.
- Breakpoint persistence lives at `~/.java-debugger/breakpoints.dbg`.
- Avoid Java record static factories that conflict with component accessors (example: record component `eof` and static `eof()`).

## Change Discipline

- Prefer surgical edits. Do not refactor unrelated code.
- Keep protocol compatibility between `BridgeMain` and `bridge_client.ts`.
- If editing Java core or CLI behavior, run `mvn test`.
- If editing Deno TUI, run `cd deno-tui && deno check src/main.tsx` and a quick `deno task bench` smoke check.

## Reference Docs

- Feature baseline for future LLM feature work: [docs/feature-baseline.md](docs/feature-baseline.md)
- Main usage, troubleshooting, and mode examples: [README.md](README.md)
- Deno TUI usage section: [README.md#deno-tui-java-engine--js-front-end](README.md#deno-tui-java-engine--js-front-end)
- Install details: [README.md#install-on-macos](README.md#install-on-macos)
- Attach source-root behavior: [README.md#attach-mode-source-roots](README.md#attach-mode-source-roots)
