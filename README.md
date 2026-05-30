# Java Debugger

Standalone Java debugger CLI built on JDI.

## Status

This is the first implementation pass. It supports launching a JVM, setting line breakpoints, and stepping with arrow-key controls.

## Build

```bash
mvn test
```

## Run

```bash
mvn -q -DskipTests package
java -jar target/java-debugger-0.1.0-SNAPSHOT.jar launch \
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
