package dev.javadebugger.core;

import java.util.List;

public record DebugSourceFile(String sourcePath, String className, List<Integer> executableLines, boolean hasMainMethod) {
}
