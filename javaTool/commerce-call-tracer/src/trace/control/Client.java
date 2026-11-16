package trace.control;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Client {
    public static Properties load(String path) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) { props.load(reader); }
        return props;
    }
    public static String send(Properties props, String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(props.getProperty("port", "43127"))), 3000);
            socket.setSoTimeout(120000);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(props.getProperty("token") + " " + command);
            String reply = in.readLine();
            if (reply == null) throw new EOFException("Agent closed connection");
            return reply;
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Client config.properties STATUS|START|STOP [thread-id] [DISCARD]");
        String reply = send(load(args[0]), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        System.out.println(reply);
        if (!reply.startsWith("OK ")) System.exit(2);
    }
}
