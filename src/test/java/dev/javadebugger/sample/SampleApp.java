package dev.javadebugger.sample;

public final class SampleApp {
    private SampleApp() {
    }

    public static void main(String[] args) throws Exception {
        Thread.sleep(300);
        int value = 1;
        value = increment(value);
        System.out.println("result=" + value);
        Thread.sleep(250);
    }

    private static int increment(int input) {
        int next = input + 1;
        return next;
    }
}
