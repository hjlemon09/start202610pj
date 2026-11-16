package trace.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import trace.control.Client;
import trace.runtime.Recorder;

/** Exercises real queued writes, rotation, UTF-8, STOP boundaries and disk conflicts. */
public final class RotationCheck {
    private static Properties props;
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        String mode = args[1];
        Path output = root.resolve("test-results/rotation/" + mode + "-" + UUID.randomUUID());
        Files.createDirectories(output);
        props = new Properties();
        props.setProperty("output", output.toString());
        props.setProperty("port", "43129");
        props.setProperty("token", UUID.randomUUID().toString());
        props.setProperty("capacity", "10000");
        props.setProperty("maxFileSizeMB", "size".equals(mode) ? "0.001" : "oversized".equals(mode) ? "0.0001" : "0");
        props.setProperty("maxRowsPerFile", "rows".equals(mode) ? "3" : "failure".equals(mode) ? "1" : "0");
        Recorder.boot(props);
        String started = command("START");
        String session = field(started, "session");
        if ("failure".equals(mode)) {
            Path conflict = output.resolve("trace-" + session + ".part0002.csv");
            Files.writeString(conflict, "existing file must be preserved", StandardCharsets.UTF_8);
            Recorder.Frame frame = Recorder.enter("rotation-conflict"); Recorder.exit(frame,"rotation-conflict",false);
            String stopped = Client.send(props, "STOP");
            check(stopped.startsWith("ERROR STOP") && stopped.contains("writer_error="), "Rotation failure must be reported: " + stopped);
            check(Files.readString(conflict).equals("existing file must be preserved"), "Never overwrite existing part");
            finish(root, mode, "PASS: rotation conflict is reported and existing file is preserved.\n" + stopped);
            return;
        }
        String method = "example.한글서비스.조회(\"comma,value\",상품상품상품상품상품상품상품상품상품상품상품상품상품상품상품상품)V";
        for (int i = 0; i < 10; i++) {
            Recorder.Frame frame = Recorder.enter(method); Recorder.exit(frame,method,i == 3);
        }
        for (int i = 0; i < 7; i++) Recorder.enter(method + i);
        String stopped = command("STOP");
        check(number(stopped,"saved") == 27 && number(stopped,"dropped") == 0, "All queued events saved: " + stopped);
        check(number(stopped,"open_at_stop") == 7, "Open call count");
        int files = (int) number(stopped,"files");
        int openFiles = (int) number(stopped,"open_files");
        if ("unlimited".equals(mode)) check(files == 1 && openFiles == 1, "Both limits disabled");
        else check(files > 1 && openFiles > 1, "Event CSV and boundary CSV both rotate");
        long cap = "size".equals(mode) ? 1048 : "oversized".equals(mode) ? 104 : 0;
        long actualBytes = 0, sequence = 0;
        String eventHeader = null, boundaryHeader = null;
        Set<String> openCalls = new HashSet<>();
        for (int i = 1; i <= files; i++) {
            Path file = part(output,session,false,i);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (eventHeader == null) eventHeader = lines.get(0);
            check(eventHeader.equals(lines.get(0)), "Every part has the same header");
            check(lines.size() > 1, "No empty trailing part");
            if ("rows".equals(mode)) check(lines.size() <= 4, "Row limit excludes header");
            if ("size".equals(mode)) check(Files.size(file) <= cap, "Byte limit includes UTF-8 and header");
            actualBytes += Files.size(file);
            for (String row : lines.subList(1,lines.size())) {
                String[] fields = row.split(",",9);
                check(Long.parseLong(fields[3]) == ++sequence, "Sequence continuous across parts");
                check(fields[8].contains("한글서비스") && !fields[8].contains("\uFFFD"), "UTF-8 row intact");
            }
        }
        check(sequence == 27 && actualBytes == number(stopped,"bytes"), "STOP byte and event totals cover every part");
        for (int i = 1; i <= openFiles; i++) {
            Path file = part(output,session,true,i);
            List<String> lines = Files.readAllLines(file,StandardCharsets.UTF_8);
            if (boundaryHeader == null) boundaryHeader = lines.get(0);
            check(boundaryHeader.equals(lines.get(0)), "Every boundary part has header");
            if ("rows".equals(mode)) check(lines.size() <= 4, "Boundary row limit");
            if ("size".equals(mode)) check(Files.size(file) <= cap, "Boundary size limit");
            for (String row : lines.subList(1,lines.size())) {
                check(row.endsWith(",OPEN_AT_STOP"), "Boundary row intact");
                check(openCalls.add(row.split(",",3)[1]), "Open call not duplicated");
            }
        }
        check(openCalls.size() == 7, "All open calls saved across boundary parts");
        if ("oversized".equals(mode)) check(number(stopped,"oversized_rows") == 27 && files == 27, "Oversized row kept whole in its own part and counted");
        String emptySession = field(command("START"),"session");
        String empty = command("STOP");
        check(number(empty,"files") == 1 && number(empty,"saved") == 0, "Empty session produces one header-only CSV");
        check(Files.readAllLines(part(output,emptySession,false,1)).size() == 1, "Header-only CSV valid");
        String discardSession = field(command("START 0 DISCARD"),"session");
        Recorder.Frame f = Recorder.enter("discard"); Recorder.exit(f,"discard",false);
        String discard = command("STOP");
        check(number(discard,"files") == 0 && number(discard,"bytes") == 0 && number(discard,"saved") == 0, "Discard creates no CSV parts");
        check(!Files.exists(part(output,discardSession,false,1)), "No discard file");
        finish(root,mode,"PASS: " + mode + " rotation, UTF-8, headers, sequence, totals, open calls, empty session, discard.\n" + stopped);
    }
    private static Path part(Path directory, String session, boolean boundary, int index) {
        return directory.resolve("trace-" + session + (boundary ? ".open" : "")
            + (index == 1 ? "" : String.format(Locale.ROOT,".part%04d",index)) + ".csv");
    }
    private static String command(String text) throws Exception {
        String reply = Client.send(props,text); check(reply.startsWith("OK "),reply); return reply;
    }
    private static String field(String text, String key) {
        for (String f : text.split(" ")) if (f.startsWith(key+"=")) return f.substring(key.length()+1);
        throw new AssertionError("Missing field " + key);
    }
    private static long number(String text, String key) { return Long.parseLong(field(text,key)); }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void finish(Path root, String mode, String report) throws Exception {
        Files.write(root.resolve("test-results/rotation-check-"+mode+".txt"),Collections.singletonList(report),StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
