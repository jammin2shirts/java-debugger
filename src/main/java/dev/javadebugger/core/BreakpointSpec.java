package dev.javadebugger.core;

public record BreakpointSpec(long id, String sourcePath, String className, int line, boolean enabled) {
}
