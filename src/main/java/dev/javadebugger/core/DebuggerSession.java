package dev.javadebugger.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.DuplicateRequestException;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

public final class DebuggerSession implements AutoCloseable {
    private static final String BREAKPOINT_ID = "breakpoint-id";
    private static final String AUTO_RESUME_STEP = "auto-resume-step";

    private final AtomicLong breakpointIds = new AtomicLong(1);
    private final Map<Long, BreakpointSpec> breakpoints = new ConcurrentHashMap<>();
    private final Map<Long, BreakpointRequest> activeBreakpointRequests = new ConcurrentHashMap<>();
    private final java.util.Set<Long> suppressedBreakpointIds = ConcurrentHashMap.newKeySet();
    private final Object breakpointInstallLock = new Object();
    private final BlockingQueue<DebuggerStop> stopQueue = new LinkedBlockingQueue<>();
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final Consumer<DebuggerStop> stopConsumer;

    private volatile VirtualMachine vm;
    private volatile ThreadReference pausedThread;
    private volatile StepRequest currentStepRequest;
    private volatile DebuggerStop currentStop;
    private volatile Thread eventLoopThread;

    public DebuggerSession(Consumer<DebuggerStop> stopConsumer) {
        this.stopConsumer = stopConsumer == null ? stop -> {
        } : stopConsumer;
    }

    public synchronized void start(LaunchConfig config) throws IOException, IllegalConnectorArgumentsException, VMStartException {
        if (vm != null) {
            throw new IllegalStateException("Session already started");
        }

        LaunchingConnector connector = findLaunchingConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue(config.commandLine());
        arguments.get("options").setValue(buildVmOptions(config.classpath()));
        Connector.Argument suspend = arguments.get("suspend");
        if (suspend != null) {
            suspend.setValue("true");
        }

        vm = connector.launch(arguments);
        pumpProcessStream(vm.process().getInputStream(), System.out);
        pumpProcessStream(vm.process().getErrorStream(), System.err);
        EventRequestManager requestManager = vm.eventRequestManager();
        ClassPrepareRequest classPrepareRequest = requestManager.createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        classPrepareRequest.enable();

        eventLoopThread = new Thread(this::eventLoop, "jdbg-event-loop");
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();
    }

    public BreakpointSpec addBreakpoint(String sourcePath, int line) {
        String normalizedSource = normalizeSourcePath(sourcePath);
        String className = inferClassName(normalizedSource);

        BreakpointSpec existingBySourceLine = findBreakpointBySourceAndLine(normalizedSource, line);
        if (existingBySourceLine != null) {
            if (!existingBySourceLine.enabled()) {
                enableBreakpoint(existingBySourceLine.id());
            }
            return breakpoints.getOrDefault(existingBySourceLine.id(), existingBySourceLine);
        }

        VirtualMachine currentVm = vm;
        boolean vmRunning = currentVm != null && terminationLatch.getCount() > 0;
        if (vmRunning) {
            try {
                BreakpointSpec existingByLocation = findBreakpointByRuntimeLocation(normalizedSource, className, line, currentVm);
                if (existingByLocation != null) {
                    if (!existingByLocation.enabled()) {
                        enableBreakpoint(existingByLocation.id());
                    }
                    return breakpoints.getOrDefault(existingByLocation.id(), existingByLocation);
                }
            } catch (VMDisconnectedException ignored) {
                vmRunning = false;
            }
        }

        long id = breakpointIds.getAndIncrement();
        BreakpointSpec spec = new BreakpointSpec(id, normalizedSource, className, line, true);
        breakpoints.put(id, spec);

        if (vmRunning) {
            try {
                applyBreakpointToLoadedTypes(spec, currentVm);
            } catch (VMDisconnectedException ignored) {
                // Target already terminated; keep breakpoint for a future restart.
            }
        }

        return spec;
    }

    public boolean removeBreakpoint(long breakpointId) {
        BreakpointSpec removed = breakpoints.remove(breakpointId);
        suppressedBreakpointIds.remove(breakpointId);
        BreakpointRequest request = activeBreakpointRequests.remove(breakpointId);
        if (request != null) {
            try {
                eventRequestManager().deleteEventRequest(request);
            } catch (RuntimeException ignored) {
            }
        }
        return removed != null;
    }

    public List<BreakpointSpec> breakpointList() {
        return breakpoints.values().stream()
                .sorted(Comparator.comparingLong(BreakpointSpec::id))
                .toList();
    }

    public boolean disableBreakpoint(long breakpointId) {
        BreakpointSpec existing = breakpoints.get(breakpointId);
        if (existing == null || !existing.enabled()) {
            return false;
        }

        breakpoints.put(breakpointId, new BreakpointSpec(existing.id(), existing.sourcePath(), existing.className(), existing.line(), false));
        BreakpointRequest request = activeBreakpointRequests.get(breakpointId);
        if (request != null) {
            request.disable();
        }
        suppressedBreakpointIds.remove(breakpointId);
        return true;
    }

    public boolean enableBreakpoint(long breakpointId) {
        BreakpointSpec existing = breakpoints.get(breakpointId);
        if (existing == null || existing.enabled()) {
            return false;
        }

        BreakpointSpec enabled = new BreakpointSpec(existing.id(), existing.sourcePath(), existing.className(), existing.line(), true);
        breakpoints.put(breakpointId, enabled);
        BreakpointRequest request = activeBreakpointRequests.get(breakpointId);
        if (request != null) {
            request.enable();
            return true;
        }

        VirtualMachine currentVm = vm;
        if (currentVm != null) {
            try {
                applyBreakpointToLoadedTypes(enabled, currentVm);
            } catch (VMDisconnectedException ignored) {
                // Target already terminated; enabled state is still preserved in memory.
            }
        }
        return true;
    }

    public int enableAllBreakpoints() {
        int changed = 0;
        for (BreakpointSpec breakpoint : breakpointList()) {
            if (enableBreakpoint(breakpoint.id())) {
                changed++;
            }
        }
        return changed;
    }

    public int disableAllBreakpoints() {
        int changed = 0;
        for (BreakpointSpec breakpoint : breakpointList()) {
            if (disableBreakpoint(breakpoint.id())) {
                changed++;
            }
        }
        return changed;
    }

    public int removeAllBreakpoints() {
        int count = breakpointList().size();
        for (BreakpointSpec breakpoint : breakpointList()) {
            removeBreakpoint(breakpoint.id());
        }
        return count;
    }

    public Optional<DebuggerStop> currentStop() {
        return Optional.ofNullable(currentStop);
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return terminationLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public DebuggerStop awaitStop(Duration timeout) throws InterruptedException {
        return stopQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void resumeExecution() {
        VirtualMachine currentVm = requireVm();
        clearPendingStops();
        if (currentStop != null && currentStop.breakpointId() >= 0) {
            resumePastCurrentBreakpoint(currentVm);
            return;
        }

        clearStepRequest();
        reenableSuppressedBreakpoints();
        currentVm.resume();
    }

    public void step(StepDirection direction) {
        VirtualMachine currentVm = requireVm();
        ThreadReference thread = pausedThread;
        if (thread == null) {
            throw new IllegalStateException("No suspended thread is available for stepping");
        }

        clearPendingStops();

        if (currentStop != null && currentStop.breakpointId() >= 0) {
            suppressBreakpoint(currentStop.breakpointId());
        }

        clearStepRequest();
        StepRequest request = eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, direction.depth());
        request.addCountFilter(1);
        request.addClassExclusionFilter("java.*");
        request.addClassExclusionFilter("javax.*");
        request.addClassExclusionFilter("sun.*");
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.putProperty(BREAKPOINT_ID, -1L);
        request.enable();
        currentStepRequest = request;
        currentVm.resume();
    }

    public boolean isRunning() {
        return vm != null && terminationLatch.getCount() > 0;
    }

    @Override
    public synchronized void close() {
        clearStepRequest();
        VirtualMachine currentVm = vm;
        vm = null;
        if (currentVm != null) {
            try {
                currentVm.dispose();
            } catch (RuntimeException ignored) {
            }
        }

        Thread loop = eventLoopThread;
        if (loop != null) {
            loop.interrupt();
        }
    }

    private void eventLoop() {
        try {
            EventQueue queue = requireVm().eventQueue();
            while (true) {
                EventSet eventSet = queue.remove();
                boolean shouldResume = true;

                for (Event event : eventSet) {
                    if (event instanceof VMStartEvent vmStartEvent) {
                        handleVmStart(vmStartEvent);
                        shouldResume = false;
                    } else if (event instanceof ClassPrepareEvent classPrepareEvent) {
                        handleClassPrepare(classPrepareEvent);
                    } else if (event instanceof BreakpointEvent breakpointEvent) {
                        handleStop("breakpoint", breakpointEvent.request().getProperty(BREAKPOINT_ID), breakpointEvent.thread(), breakpointEvent.location());
                        shouldResume = false;
                    } else if (event instanceof StepEvent stepEvent) {
                        Object autoResume = stepEvent.request().getProperty(AUTO_RESUME_STEP);
                        if (Boolean.TRUE.equals(autoResume)) {
                            reenableSuppressedBreakpoints();
                            shouldResume = true;
                        } else {
                            handleStop("step", stepEvent.request().getProperty(BREAKPOINT_ID), stepEvent.thread(), stepEvent.location());
                            reenableSuppressedBreakpoints();
                            shouldResume = false;
                        }
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        handleTermination();
                        shouldResume = false;
                    }
                }

                if (shouldResume) {
                    eventSet.resume();
                }

                if (terminationLatch.getCount() == 0) {
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (VMDisconnectedException ignored) {
            handleTermination();
        }
    }

    private void handleClassPrepare(ClassPrepareEvent event) {
        ReferenceType referenceType = event.referenceType();
        for (BreakpointSpec breakpoint : breakpointList()) {
            if (breakpoint.enabled() && !activeBreakpointRequests.containsKey(breakpoint.id()) && matchesBreakpointTarget(referenceType, breakpoint)) {
                applyBreakpointToReferenceType(breakpoint, referenceType);
            }
        }
    }

    private void handleVmStart(VMStartEvent event) {
        pausedThread = event.thread();
        DebuggerStop stop = DebuggerStop.of(
                "start",
                -1L,
            null,
                null,
                null,
                -1,
                event.thread() == null ? "unknown" : event.thread().name(),
                false);
        currentStop = stop;
            publishStop(stop);
        stopConsumer.accept(stop);
    }

    private void handleStop(String kind, Object requestBreakpointId, ThreadReference thread, Location location) {
        clearStepRequest();
        pausedThread = thread;
        long breakpointId = requestBreakpointId instanceof Long value ? value : -1L;
        if (breakpointId >= 0) {
            suppressBreakpoint(breakpointId);
        }
        DebuggerStop stop = DebuggerStop.of(
                kind,
                breakpointId,
                resolveSourcePath(breakpointId, location),
                safeSourceName(location),
                location.declaringType().name(),
                location.lineNumber(),
                thread.name(),
                false);
        currentStop = stop;
            publishStop(stop);
        stopConsumer.accept(stop);
    }

    private void handleTermination() {
        if (terminationLatch.getCount() == 0) {
            return;
        }

        currentStop = DebuggerStop.terminal();
        publishStop(currentStop);
        stopConsumer.accept(currentStop);
        terminationLatch.countDown();
    }

    private void publishStop(DebuggerStop stop) {
        clearPendingStops();
        stopQueue.offer(stop);
    }

    private void clearPendingStops() {
        stopQueue.clear();
    }

    private void applyBreakpointToLoadedTypes(BreakpointSpec spec, VirtualMachine currentVm) {
        if (!spec.enabled()) {
            return;
        }

        try {
            if (spec.className() != null) {
                for (ReferenceType referenceType : currentVm.classesByName(spec.className())) {
                    applyBreakpointToReferenceType(spec, referenceType);
                }
                return;
            }

            for (ReferenceType referenceType : currentVm.allClasses()) {
                if (matchesBreakpointTarget(referenceType, spec)) {
                    applyBreakpointToReferenceType(spec, referenceType);
                }
            }
        } catch (VMDisconnectedException ignored) {
            // Target disconnected while resolving or installing breakpoints.
        }
    }

    private void applyBreakpointToReferenceType(BreakpointSpec spec, ReferenceType referenceType) {
        if (!spec.enabled()) {
            return;
        }

        synchronized (breakpointInstallLock) {
            if (activeBreakpointRequests.containsKey(spec.id())) {
                return;
            }

            try {
                List<Location> locations = referenceType.locationsOfLine(spec.line());
                if (locations.isEmpty()) {
                    return;
                }

                Location location = locations.get(0);
                BreakpointSpec existing = findBreakpointByActiveLocation(location);
                if (existing != null && existing.id() != spec.id()) {
                    return;
                }

                BreakpointRequest request = eventRequestManager().createBreakpointRequest(location);
                request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                request.putProperty(BREAKPOINT_ID, spec.id());
                request.enable();
                activeBreakpointRequests.put(spec.id(), request);
            } catch (AbsentInformationException ignored) {
            } catch (DuplicateRequestException ignored) {
            } catch (VMDisconnectedException ignored) {
                // Target disconnected during request creation; safe to ignore.
            }
        }
    }

    private BreakpointSpec findBreakpointBySourceAndLine(String sourcePath, int line) {
        for (BreakpointSpec breakpoint : breakpointList()) {
            if (breakpoint.line() == line && breakpoint.sourcePath().equals(sourcePath)) {
                return breakpoint;
            }
        }
        return null;
    }

    private BreakpointSpec findBreakpointByRuntimeLocation(String sourcePath, String className, int line, VirtualMachine currentVm) {
        BreakpointSpec probe = new BreakpointSpec(-1L, sourcePath, className, line, true);

        if (probe.className() != null) {
            for (ReferenceType referenceType : currentVm.classesByName(probe.className())) {
                BreakpointSpec found = findBreakpointByRuntimeLocation(referenceType, probe);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        for (ReferenceType referenceType : currentVm.allClasses()) {
            if (!matchesBreakpointTarget(referenceType, probe)) {
                continue;
            }

            BreakpointSpec found = findBreakpointByRuntimeLocation(referenceType, probe);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private BreakpointSpec findBreakpointByRuntimeLocation(ReferenceType referenceType, BreakpointSpec probe) {
        try {
            List<Location> locations = referenceType.locationsOfLine(probe.line());
            if (locations.isEmpty()) {
                return null;
            }
            return findBreakpointByActiveLocation(locations.get(0));
        } catch (AbsentInformationException ignored) {
            return null;
        }
    }

    private BreakpointSpec findBreakpointByActiveLocation(Location location) {
        for (Map.Entry<Long, BreakpointRequest> entry : activeBreakpointRequests.entrySet()) {
            BreakpointRequest request = entry.getValue();
            if (request != null && location.equals(request.location())) {
                return breakpoints.get(entry.getKey());
            }
        }
        return null;
    }

    private boolean matchesBreakpointTarget(ReferenceType referenceType, BreakpointSpec spec) {
        if (spec.className() != null && spec.className().equals(referenceType.name())) {
            return true;
        }

        String sourceFileName = PathUtils.fileName(spec.sourcePath());
        try {
            for (String sourceName : referenceType.sourceNames(null)) {
                if (sourceName.equals(spec.sourcePath()) || sourceName.endsWith(sourceFileName) || sourceName.endsWith("/" + sourceFileName)) {
                    return true;
                }
            }
        } catch (AbsentInformationException ignored) {
        }
        return false;
    }

    private void clearStepRequest() {
        StepRequest stepRequest = currentStepRequest;
        currentStepRequest = null;
        if (stepRequest != null) {
            try {
                eventRequestManager().deleteEventRequest(stepRequest);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void resumePastCurrentBreakpoint(VirtualMachine currentVm) {
        ThreadReference thread = pausedThread;
        if (thread == null) {
            clearStepRequest();
            reenableSuppressedBreakpoints();
            currentVm.resume();
            return;
        }

        clearStepRequest();
        StepRequest request = eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
        request.addCountFilter(1);
        request.addClassExclusionFilter("java.*");
        request.addClassExclusionFilter("javax.*");
        request.addClassExclusionFilter("sun.*");
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.putProperty(BREAKPOINT_ID, -1L);
        request.putProperty(AUTO_RESUME_STEP, Boolean.TRUE);
        request.enable();
        currentStepRequest = request;
        currentVm.resume();
    }

    private void suppressBreakpoint(long breakpointId) {
        BreakpointRequest request = activeBreakpointRequests.get(breakpointId);
        if (request != null) {
            request.disable();
            suppressedBreakpointIds.add(breakpointId);
        }
    }

    private void reenableSuppressedBreakpoints() {
        if (suppressedBreakpointIds.isEmpty()) {
            return;
        }

        for (Long breakpointId : List.copyOf(suppressedBreakpointIds)) {
            BreakpointSpec spec = breakpoints.get(breakpointId);
            if (spec == null || !spec.enabled()) {
                suppressedBreakpointIds.remove(breakpointId);
                continue;
            }
            BreakpointRequest request = activeBreakpointRequests.get(breakpointId);
            if (request != null) {
                request.enable();
            }
            suppressedBreakpointIds.remove(breakpointId);
        }
    }

    private EventRequestManager eventRequestManager() {
        return requireVm().eventRequestManager();
    }

    private VirtualMachine requireVm() {
        VirtualMachine currentVm = vm;
        if (currentVm == null) {
            throw new IllegalStateException("Debugger session has not been started");
        }
        return currentVm;
    }

    private LaunchingConnector findLaunchingConnector() {
        return Bootstrap.virtualMachineManager().launchingConnectors().stream()
                .filter(connector -> "com.sun.jdi.CommandLineLaunch".equals(connector.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No command line launching connector available"));
    }

    private String buildVmOptions(String classpath) {
        return "-cp " + classpath;
    }

    private String normalizeSourcePath(String sourcePath) {
        return PathUtils.normalize(sourcePath);
    }

    private String inferClassName(String sourcePath) {
        String normalized = normalizeSourcePath(sourcePath);
        String marker = "/src/";
        int sourceIndex = normalized.lastIndexOf(marker);
        if (sourceIndex < 0) {
            return null;
        }

        String tail = normalized.substring(sourceIndex + marker.length());
        if (!tail.contains("/java/")) {
            return null;
        }

        int javaIndex = tail.indexOf("/java/");
        String classPath = tail.substring(javaIndex + "/java/".length());
        if (!classPath.endsWith(".java")) {
            return null;
        }

        String withoutExtension = classPath.substring(0, classPath.length() - ".java".length());
        return withoutExtension.replace('/', '.');
    }

    private String safeSourceName(Location location) {
        try {
            return location.sourceName();
        } catch (AbsentInformationException exception) {
            return null;
        }
    }

    private String safeSourcePath(Location location) {
        try {
            return location.sourcePath();
        } catch (AbsentInformationException exception) {
            return null;
        }
    }

    private String resolveSourcePath(long breakpointId, Location location) {
        if (breakpointId >= 0) {
            BreakpointSpec spec = breakpoints.get(breakpointId);
            if (spec != null) {
                return spec.sourcePath();
            }
        }
        return safeSourcePath(location);
    }

    private void pumpProcessStream(InputStream inputStream, OutputStream outputStream) {
        Thread thread = new Thread(() -> {
            try (InputStream in = inputStream) {
                in.transferTo(outputStream);
                outputStream.flush();
            } catch (IOException ignored) {
            }
        }, "jdbg-process-stream");
        thread.setDaemon(true);
        thread.start();
    }

    private static final class PathUtils {
        private PathUtils() {
        }

        private static String normalize(String value) {
            return value.replace('\\', '/');
        }

        private static String fileName(String value) {
            String normalized = normalize(value);
            int index = normalized.lastIndexOf('/');
            return index >= 0 ? normalized.substring(index + 1) : normalized;
        }
    }
}
