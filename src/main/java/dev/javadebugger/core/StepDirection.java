package dev.javadebugger.core;

public enum StepDirection {
    INTO(com.sun.jdi.request.StepRequest.STEP_INTO),
    OVER(com.sun.jdi.request.StepRequest.STEP_OVER),
    OUT(com.sun.jdi.request.StepRequest.STEP_OUT);

    private final int depth;

    StepDirection(int depth) {
        this.depth = depth;
    }

    public int depth() {
        return depth;
    }
}
