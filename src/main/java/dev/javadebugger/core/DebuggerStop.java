package dev.javadebugger.core;

public record DebuggerStop(String kind,
                           long breakpointId,
                           String sourcePath,
                           String sourceName,
                           String className,
                           int lineNumber,
                           String threadName,
                           boolean terminated) {
    public static DebuggerStop of(String kind,
                                  long breakpointId,
                                  String sourcePath,
                                  String sourceName,
                                  String className,
                                  int lineNumber,
                                  String threadName,
                                  boolean terminated) {
        return new DebuggerStop(kind, breakpointId, sourcePath, sourceName, className, lineNumber, threadName, terminated);
    }

    public static DebuggerStop terminal() {
        return new DebuggerStop("terminated", -1L, null, null, null, -1, null, true);
    }
}
