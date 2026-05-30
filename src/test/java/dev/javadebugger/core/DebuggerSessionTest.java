package dev.javadebugger.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DebuggerSessionTest {
    @Test
    void launchesSampleAppAndSupportsBreakpointPlusStepControls() throws Exception {
        Path sampleSource = Path.of("src/test/java/dev/javadebugger/sample/SampleApp.java");
        int breakpointLine = findLine(sampleSource, "value = increment(value);");
        BlockingQueue<DebuggerStop> stops = new LinkedBlockingQueue<>();
        try (DebuggerSession session = new DebuggerSession(stops::offer)) {
            session.start(new LaunchConfig(
                    "dev.javadebugger.sample.SampleApp",
                    System.getProperty("java.class.path"),
                    List.of()));

                DebuggerStop startStop = awaitStop(stops);
                assertEquals("start", startStop.kind());

            session.addBreakpoint(sampleSource.toString(), breakpointLine);
            session.resumeExecution();

            DebuggerStop breakpointStop = awaitStop(stops);
            assertEquals("breakpoint", breakpointStop.kind());
            assertEquals(breakpointLine, breakpointStop.lineNumber());

            session.step(StepDirection.INTO);
            DebuggerStop stepIntoStop = awaitStop(stops);
            assertEquals("step", stepIntoStop.kind());
            assertTrue(stepIntoStop.lineNumber() > 0);
            assertTrue(stepIntoStop.lineNumber() != breakpointLine);

            session.step(StepDirection.OVER);
            DebuggerStop stepOverStop = awaitStop(stops);
            assertEquals("step", stepOverStop.kind());
            assertTrue(stepOverStop.lineNumber() > 0);

            session.step(StepDirection.OUT);
            DebuggerStop stepOutStop = awaitStop(stops);
            assertEquals("step", stepOutStop.kind());
            assertTrue(stepOutStop.lineNumber() > 0);

            session.resumeExecution();
            assertTrue(session.awaitTermination(Duration.ofSeconds(10)));
        }
    }

    @Test
    void breakpointRegistryIsOrderedById() {
        DebuggerSession session = new DebuggerSession(stop -> {
        });
        BreakpointSpec first = session.addBreakpoint("alpha/One.java", 10);
        BreakpointSpec second = session.addBreakpoint("beta/Two.java", 20);

        assertEquals(first.id(), session.breakpointList().get(0).id());
        assertEquals(second.id(), session.breakpointList().get(1).id());
    }

    private static DebuggerStop awaitStop(BlockingQueue<DebuggerStop> stops) throws InterruptedException {
        DebuggerStop stop = stops.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        if (stop == null) {
            fail("Timed out waiting for debugger stop");
        }
        assertNotNull(stop);
        return stop;
    }

    private static int findLine(Path path, String fragment) throws IOException {
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Unable to find line containing: " + fragment);
    }
}
