# Java Debugger

Standalone Java debugger CLI built on JDI.

## Status

This is the first implementation pass. It supports launching a JVM, setting line breakpoints, and stepping with arrow-key controls.

## Build

```bash
mvn test
```

## Install on macOS

```bash
./scripts/install-jdbg.sh
```

This installs:

- `jdbg` command in `/usr/local/bin` if writable, otherwise `~/.local/bin`
- debugger jar in `~/.local/share/jdbg/java-debugger.jar`

Uninstall:

```bash
./scripts/uninstall-jdbg.sh
```

## Run

```bash
mvn -q -DskipTests package

# default mode: attach to 127.0.0.1:5005
jdbg

# attach with a different port/host
jdbg --port 6006 --host 127.0.0.1

# attach and point debugger to the target app's source tree for source snippets
jdbg --port 5005 \
  --source-root /absolute/path/to/other-app/src/main/java

# launch a new JVM under debugger control (explicit launch mode)
jdbg --launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath target/test-classes:target/classes

# equivalent subcommand form still supported
jdbg launch \
  --main-class dev.javadebugger.sample.SampleApp \
  --classpath target/test-classes:target/classes

# in the debugger shell
# 1) use `files` to set a breakpoint with cursor + space
# 2) start execution
continue
```

Interactive commands after launch:

- `start` / `continue`
- `status`
- `breaks`
- `clear <id>`
- `files` (browse project files, set/remove breakpoints with cursor + space)
- `manage` (enable/disable/delete breakpoints)
- `state clear` (clear saved breakpoint state from disk)
- `restart`
- `quit`

Shortcut interface:

- `c` start/continue
- Arrow keys at main prompt: `↑` step into, `→` step over, `←` step out, `↓` start/continue
- `x` prompt for breakpoint id to clear
- `f` open project file browser
- `m` open breakpoint manager
- `s` status
- `l` list breakpoints
- `r` restart current debug flow (keeps breakpoints)
- `q` quit
- `enter` repeats the last `continue` or `step` command

Runtime notifications:

- After `continue`, the UI reports when execution has moved past the current stop and is still running.
- When the target process ends normally, the UI confirms completion and suggests `restart` (`r`).
- The TUI always shows the full current breakpoint list.
- After `restart`, the UI reports which breakpoints were restored.

Persistent breakpoints:

- Breakpoints are saved automatically between CLI runs.
- Saved state path: `~/.java-debugger/breakpoints.dbg`.
- Saved fields: source path, line, and enabled/disabled state.
- Use `state clear` to remove saved breakpoint state.

Breakpoint workflows:

- `files`: browse source files with up/down, press enter to open a file, then use up/down and `space` to toggle a breakpoint on the highlighted line.
- `manage`: review all breakpoints, use `space` to enable/disable selected, `d` to delete selected, `a` enable all, `n` disable all, `x` delete all.

Attach-mode file browsing:

- In `attach` mode, `files` lists source files reported by the connected JVM's loaded classes (not just local workspace files).
- The list is filtered to hide common dependency/framework packages so you can focus on app/project classes.
- If that filtered list is empty, the picker falls back to all attached JVM source files.
- If local source text is available, the line picker shows source lines.
- If local source text is unavailable, the line picker shows executable line numbers from JVM debug metadata, and `space` toggles breakpoints on those lines.
- In `attach` mode, the debugger does not auto-scan your filesystem for source roots.
- If you run `jdbg attach` from a target project root, the debugger automatically uses `./src/main/java` and `./src/test/java` (if present) as source roots.
- Use `--source-root` to explicitly provide source directories for the target JVM project so stop locations can render real source snippets.

CLI mode defaults:

- Running `jdbg` with no mode flags uses `attach` mode by default.
- Default attach target is `127.0.0.1:5005`.
- Override attach connection with `--host` and `--port`.
- Use `--launch` (or `launch`) to switch to launch-under-debugger mode.
