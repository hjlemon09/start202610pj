package trace.test;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import trace.control.Client;

/** Uses actual JDWP line breakpoints; controls the Agent from a separate JVM. */
public final class BreakpointCheck {
    private static Path root, config;
    private static Properties props;
    private static final List<String> report = new ArrayList<>();
    private static int starts, stops;
    private static String previousSession;
    private static long intervalStart;
    private static Process target;
    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]).toAbsolutePath();
        Path results = root.resolve("test-results"); Files.createDirectories(results);
        config = root.resolve("config/demo.properties"); props = Client.load(config.toString());
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> launch = connector.defaultArguments();
        launch.get("main").setValue("demo.shop.Demo 2");
        launch.get("options").setValue("-javaagent:\"" + root.resolve("dist/trace-agent.jar") + "=" + config
            + "\" -cp \"" + root.resolve("dist/trace-demo.jar") + "\"");
        VirtualMachine vm = connector.launch(launch);
        target = vm.process();
        pipe(target.getInputStream(), results.resolve("target.stdout.txt"));
        pipe(target.getErrorStream(), results.resolve("target.stderr.txt"));
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("demo.shop.Demo"); prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); prepare.enable();
            boolean done = false;
            while (!done) {
                EventSet set = vm.eventQueue().remove(30000);
                check(set != null, "Debugger event within 30s");
                for (Event event : set) {
                    if (event instanceof ClassPrepareEvent) {
                        ReferenceType type = ((ClassPrepareEvent)event).referenceType();
                        List<String> source = Files.readAllLines(root.resolve("src/demo/shop/Demo.java"));
                        for (int i = 0; i < source.size(); i++) {
                            if (source.get(i).contains("_BREAKPOINT:")) {
                                List<Location> locations = type.locationsOfLine(i + 1);
                                check(!locations.isEmpty(), "Executable boundary line " + (i + 1));
                                BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(locations.get(0));
                                bp.putProperty("boundary", source.get(i).contains("START_BREAKPOINT") ? "START" : "STOP");
                                bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); bp.enable();
                            }
                        }
                    } else if (event instanceof BreakpointEvent) {
                        BreakpointEvent bp = (BreakpointEvent)event;
                        // JDI uniqueID is a debugger object id, NOT java.lang.Thread.getId().
                        Field tid = bp.thread().referenceType().fieldByName("tid");
                        long javaThread = ((LongValue)bp.thread().getValue(tid)).longValue();
                        check(bp.thread().isSuspended(), "Request thread is suspended");
                        String kind = (String)bp.request().getProperty("boundary");
                        if ("START".equals(kind)) {
                            String status = command("STATUS");
                            check(status.contains("transform_errors=0"), "No instrumentation errors: " + status);
                            String reply = command("START", Long.toString(javaThread));
                            check(reply.startsWith("OK START"), "START responds while breakpoint suspended");
                            check(command("START").startsWith("ERROR already"), "Duplicate START rejected");
                            previousSession = value(reply, "session"); starts++;
                            intervalStart = System.nanoTime();
                        } else {
                            double intervalMs = (System.nanoTime() - intervalStart) / 1e6;
                            String reply = command("STOP"); stops++;
                            check(reply.startsWith("OK STOP"), "STOP flushes while breakpoint suspended");
                            check(command("STOP").startsWith("ERROR not"), "Duplicate STOP rejected");
                            check("0".equals(value(reply, "dropped")), "No dropped events");
                            check("0".equals(value(reply, "open_at_stop")), "All calls in synchronous interval closed");
                            check(value(reply, "events").equals(value(reply, "saved")), "All events saved");
                            inspect(root.resolve("logs/trace-" + previousSession + ".csv"), javaThread);
                            report.add("JDI Resume-to-stop-event delivery ms (includes debugger transport): " + intervalMs);
                            report.add(reply);
                        }
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) { done = true; }
                }
                try { set.resume(); } catch (VMDisconnectedException ignored) { done = true; }
            }
            check(starts == 2 && stops == 2, "Two independent sessions completed");
            check(target.waitFor(10, TimeUnit.SECONDS) && target.exitValue() == 0, "Target exits normally");
            report.add("PASS: real JDWP breakpoints, SUSPEND_EVENT_THREAD; CLI controller is a separate JVM.");
            report.add("NOT TESTED: clicking IntelliJ/Eclipse UI, SAP Commerce, production performance.");
            Files.write(results.resolve("breakpoint-check.txt"), report, StandardCharsets.UTF_8);
            for (String line : report) System.out.println(line);
        } finally {
            if (target.isAlive()) target.destroyForcibly();
        }
    }
    private static void inspect(Path file, long thread) throws Exception {
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        String text = String.join("\n", rows);
        check(text.contains("Demo.facade") && text.contains("Demo.service") && text.contains("Demo.dao"), "Facade-Service-DAO recorded");
        check(text.contains(",THROW,") && text.contains("Demo.failingService"), "Exceptional exit recorded");
        check(!text.contains("Demo.before") && !text.contains("Demo.after") && !text.contains("Demo.stopBoundary"), "Outside calls excluded");
        check(!text.contains("Demo.outer") && !text.contains("Demo.main"), "Previous-session exits not leaked");
        check(!text.contains("Demo.background"), "Other request thread filtered");
        Map<String, Integer> balance = new HashMap<>();
        long seq = 0;
        boolean recursiveDepth = false;
        for (String row : rows.subList(1, rows.size())) {
            String[] parts = row.split(",", 9);
            check(parts[1].equals(Long.toString(thread)), "Thread ID matches request");
            check(Long.parseLong(parts[3]) == ++seq, "Thread sequence contiguous");
            check(parts[0].equals("\"" + previousSession + "\""), "Session ID consistent");
            int delta = "ENTER".equals(parts[6]) ? 1 : -1;
            balance.put(parts[4], balance.getOrDefault(parts[4], 0) + delta);
            if (parts[8].contains("Demo.recursive") && "2".equals(parts[5])) recursiveDepth = true;
        }
        check(recursiveDepth, "Instrumented recursive depth preserved");
        check(balance.values().stream().allMatch(n -> n == 0), "Every call ID balanced");
        report.add("CSV verified: " + file + " rows=" + (rows.size() - 1));
    }
    private static String command(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin/java.exe").toString());
        cmd.add("-cp"); cmd.add(root.resolve("dist/trace-controller.jar").toString());
        cmd.add("execute.Execute"); cmd.add(config.toString()); Collections.addAll(cmd, args);
        long begin = System.nanoTime();
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Control command timeout"); }
        String response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        report.add(String.join(" ", args) + " response_ms_including_controller_JVM_launch=" + ((System.nanoTime() - begin) / 1e6));
        return response;
    }
    private static String value(String reply, String key) {
        for (String field : reply.split(" ")) if (field.startsWith(key + "=")) return field.substring(key.length() + 1);
        throw new AssertionError("Missing " + key + ": " + reply);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void pipe(InputStream in, Path destination) {
        Thread thread = new Thread(() -> { try { Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {} });
        thread.setDaemon(true); thread.start();
    }
}
