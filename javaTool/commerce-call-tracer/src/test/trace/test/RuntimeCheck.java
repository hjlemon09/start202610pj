package trace.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import trace.control.Client;
import trace.runtime.Recorder;

/** Session boundary and overflow contract checks against the exact bootstrap helper API. */
public final class RuntimeCheck {
    static Properties props;
    static Path output;
    static List<String> report = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        output = root.resolve("test-results/runtime"); Files.createDirectories(output);
        props = new Properties();
        props.setProperty("port", "43128"); props.setProperty("token", UUID.randomUUID().toString());
        props.setProperty("output", output.toString()); props.setProperty("capacity", "1");
        Recorder.boot(props);
        Properties wrong = new Properties(); wrong.putAll(props); wrong.setProperty("token", "wrong");
        check(Client.send(wrong, "STATUS").startsWith("ERROR authentication"), "Authentication rejection");
        Recorder.Frame off = Recorder.enter("entered-before-start");
        cmd("START"); Recorder.exit(off, "entered-before-start", false);
        Recorder.Frame crossing = Recorder.enter("crosses-sessions");
        String first = cmd("STOP");
        check(val(first,"partial_exits") == 1, "Entry before START labelled PARTIAL_EXIT");
        check(val(first,"open_at_stop") == 1, "Unfinished call marked OPEN_AT_STOP");
        check(Files.readString(output.resolve("trace-" + field(first,"session") + ".open.csv")).contains("crosses-sessions"), "Boundary sidecar identifies open call");
        cmd("START"); Recorder.exit(crossing, "crosses-sessions", false);
        String second = cmd("STOP");
        check(val(second,"events") == 0, "Previous session EXIT excluded from later session");
        cmd("START 999999999");
        Recorder.Frame filtered = Recorder.enter("wrong-thread"); Recorder.exit(filtered,"wrong-thread",false);
        check(val(cmd("STOP"),"events") == 0, "Thread filter excludes current thread");
        cmd("START 0 DISCARD");
        for (int i=0;i<100000;i++) { Recorder.Frame f = Recorder.enter("burst"); Recorder.exit(f,"burst",false); }
        String burst = cmd("STOP");
        check(val(burst,"events") == 200000, "All attempted events counted");
        check(val(burst,"dropped") > 0, "Saturation reports loss");
        check(val(burst,"events") == val(burst,"consumed") + val(burst,"dropped"), "Generated = consumed + dropped");
        check(val(burst,"saved") == 0 && field(burst,"file").equals("DISCARD"), "Discard mode uses same event queue without CSV");
        report.add("PASS: boundary markers, cross-session exclusion, authentication, filter, overflow, discard.");
        report.add(first); report.add(second); report.add(burst);
        Files.write(root.resolve("test-results/runtime-check.txt"), report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }
    static String cmd(String command) throws Exception {
        String reply = Client.send(props, command);
        check(reply.startsWith("OK "), command + ": " + reply); return reply;
    }
    static String field(String text, String key) {
        for (String value:text.split(" ")) if(value.startsWith(key+"=")) return value.substring(key.length()+1);
        throw new AssertionError("Missing " + key);
    }
    static long val(String text,String key) { return Long.parseLong(field(text,key)); }
    static void check(boolean condition,String text) { if(!condition) throw new AssertionError(text); }
}
