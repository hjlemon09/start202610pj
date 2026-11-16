package trace.runtime;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bootstrap-loaded helper. No Byte Buddy or application dependencies. */
public final class Recorder {
    private static final Object GATE = new Object();
    private static final ThreadLocal<State> LOCAL = new ThreadLocal<State>();
    private static volatile Session active;
    private static volatile String last = "IDLE";
    private static volatile long transformed, transformErrors;
    private static Path output;
    private static int capacity;
    private static String secret;
    private static long serial;
    private static ServerSocket server;

    public static final class State {
        final Session session;
        long sequence;
        int depth;
        State(Session session) { this.session = session; }
    }
    public static final class Frame {
        final State state;
        final long call;
        final int depth;
        final String method;
        Frame(State state, long call, int depth, String method) {
            this.state = state; this.call = call; this.depth = depth; this.method = method;
        }
    }
    private static final class Session {
        final String id = System.currentTimeMillis() + "-" + (++serial);
        final long start = System.nanoTime();
        final long threadFilter;
        final boolean save;
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(capacity);
        final Map<Frame, String> open = new IdentityHashMap<Frame, String>();
        final Path file = output.resolve("trace-" + id + ".csv");
        long enters, exits, partialExits, dropped, calls;
        volatile long consumed, saved, bytes;
        volatile boolean closed;
        volatile String error = "";
        Thread writer;
        Session(long threadFilter, boolean save) { this.threadFilter = threadFilter; this.save = save; }
        void startWriter() throws IOException {
            final BufferedWriter out = save ? Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) : null;
            if (out != null) {
                try {
                    out.write("session,thread_id,thread_name,sequence,call_id,depth,event,relative_ns,method\n");
                    out.flush();
                } catch (IOException ex) { out.close(); throw ex; }
            }
            writer = new Thread(new Runnable() { public void run() {
                try {
                    while (!closed || !queue.isEmpty()) {
                        String row = queue.poll(20, TimeUnit.MILLISECONDS);
                        if (row != null) {
                            consumed++;
                            if (out != null && error.isEmpty()) {
                                out.write(row); out.newLine(); saved++;
                            }
                        }
                    }
                } catch (Exception ex) { error = ex.toString(); }
                finally {
                    if (out != null) {
                        try { out.close(); bytes = Files.size(file); }
                        catch (IOException ex) { error = ex.toString(); }
                    }
                }
            }}, "call-tracer-writer");
            writer.setDaemon(true); writer.start();
        }
    }
    public static void transformed(boolean ok) {
        synchronized (GATE) { if (ok) transformed++; else transformErrors++; }
    }
    public static void boot(Properties props) throws IOException {
        output = Paths.get(props.getProperty("output")).toAbsolutePath();
        Files.createDirectories(output);
        capacity = Integer.parseInt(props.getProperty("capacity", "100000"));
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        secret = props.getProperty("token", "");
        if (secret.length() < 16 || secret.indexOf(' ') >= 0) throw new IllegalArgumentException("token: 16+ characters, no spaces");
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Integer.parseInt(props.getProperty("port", "43127"))));
        Thread control = new Thread(new Runnable() { public void run() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    String line = in.readLine();
                    try { out.println(command(line)); }
                    catch (Exception ex) { out.println("ERROR " + ex.toString().replace('\n', ' ')); }
                } catch (IOException ex) { System.err.println("[call-tracer] control: " + ex); }
            }
        }}, "call-tracer-control");
        control.setDaemon(true); control.start();
        System.err.println("[call-tracer] READY 127.0.0.1:" + server.getLocalPort() + " output=" + output);
    }
    private static String command(String line) throws Exception {
        if (line == null || !line.startsWith(secret + " ")) return "ERROR authentication";
        String[] args = line.substring(secret.length() + 1).trim().split("\\s+");
        if ("STATUS".equals(args[0])) return "OK " + (active == null ? last : "RECORDING session=" + active.id)
            + " transformed=" + transformed + " transform_errors=" + transformErrors;
        if ("START".equals(args[0])) {
            long filter = args.length > 1 ? Long.parseLong(args[1]) : 0;
            if (filter < 0) throw new IllegalArgumentException("thread id must be >= 0");
            boolean save = args.length <= 2 || !"DISCARD".equals(args[2]);
            synchronized (GATE) {
                if (active != null) return "ERROR already recording";
                Session session = new Session(filter, save);
                session.startWriter();
                active = session;
                return "OK START session=" + session.id + " thread=" + filter + " save=" + save;
            }
        }
        if ("STOP".equals(args[0])) {
            Session session;
            long begin = System.nanoTime();
            long open;
            synchronized (GATE) {
                session = active;
                if (session == null) return "ERROR not recording";
                active = null;
                open = session.open.size();
                // A sidecar records every accepted call still open at STOP, even when the event queue overflows.
                if (session.save) {
                    Path boundaries = output.resolve("trace-" + session.id + ".open.csv");
                    try (BufferedWriter out = Files.newBufferedWriter(boundaries, StandardCharsets.UTF_8)) {
                        out.write("session,call_id,depth,thread_id,thread_name,method,boundary\n");
                        for (Map.Entry<Frame, String> entry : session.open.entrySet()) {
                            Frame f = entry.getKey();
                            out.write(csv(session.id) + "," + f.call + "," + f.depth + "," + entry.getValue()
                                + "," + csv(f.method) + ",OPEN_AT_STOP\n");
                        }
                    } catch (IOException ex) { session.error = ex.toString(); }
                }
                session.open.clear();
                session.closed = true;
            }
            session.writer.join();
            double stopMs = (System.nanoTime() - begin) / 1000000.0;
            last = "STOP session=" + session.id + " enters=" + session.enters + " exits=" + session.exits
                + " partial_exits=" + session.partialExits + " open_at_stop=" + open
                + " events=" + (session.enters + session.exits + session.partialExits)
                + " consumed=" + session.consumed + " saved=" + session.saved + " dropped=" + session.dropped
                + " bytes=" + session.bytes + " stop_ms=" + String.format(Locale.ROOT, "%.3f", stopMs)
                + " file=" + (session.save ? session.file : "DISCARD")
                + (session.error.isEmpty() ? "" : " writer_error=" + session.error);
            if (session.save) {
                try { Files.write(output.resolve("trace-" + session.id + ".summary.txt"),
                    Collections.singletonList(last), StandardCharsets.UTF_8); }
                catch (IOException ex) { return "ERROR summary write " + ex + " " + last; }
            }
            return (session.error.isEmpty() ? "OK " : "ERROR ") + last;
        }
        return "ERROR command";
    }
    public static Frame enter(String method) {
        if (active == null) return null;
        synchronized (GATE) {
            Session s = active;
            Thread t = Thread.currentThread();
            if (s == null || (s.threadFilter != 0 && s.threadFilter != t.getId())) return null;
            State state = LOCAL.get();
            if (state == null || state.session != s) { state = new State(s); LOCAL.set(state); }
            Frame frame = new Frame(state, ++s.calls, state.depth++, method);
            s.open.put(frame, t.getId() + "," + csv(t.getName()));
            s.enters++;
            emit(s, state, frame.call, frame.depth, "ENTER", method, t);
            return frame;
        }
    }
    public static void exit(Frame frame, String method, boolean thrown) {
        if (active == null) return;
        synchronized (GATE) {
            Session s = active;
            Thread t = Thread.currentThread();
            if (frame != null) {
                frame.state.depth--;
                if (s != frame.state.session) return; // Never leak a previous session's EXIT into a new one.
                s.open.remove(frame); s.exits++;
                emit(s, frame.state, frame.call, frame.depth, thrown ? "THROW" : "EXIT", method, t);
            } else if (s != null && (s.threadFilter == 0 || s.threadFilter == t.getId())) {
                State state = LOCAL.get();
                if (state == null || state.session != s) { state = new State(s); LOCAL.set(state); }
                s.partialExits++;
                emit(s, state, 0, -1, thrown ? "PARTIAL_THROW" : "PARTIAL_EXIT", method, t);
            }
        }
    }
    private static void emit(Session s, State state, long call, int depth, String kind, String method, Thread t) {
        String row = csv(s.id) + "," + t.getId() + "," + csv(t.getName()) + "," + (++state.sequence)
            + "," + call + "," + depth + "," + kind + "," + (System.nanoTime() - s.start) + "," + csv(method);
        if (!s.queue.offer(row)) s.dropped++;
    }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + "\""; }
}
