package trace.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import trace.control.Client;

public final class LoaderCheck {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        String java = args.length > 1 ? args[1] : Paths.get(System.getProperty("java.home"), "bin/java.exe").toString();
        Path jar = root.resolve("dist/trace-demo.jar"), config = root.resolve("config/demo.properties");
        Path stderr = root.resolve("test-results/isolated-stderr.txt");
        Process target = new ProcessBuilder(java, "-javaagent:" + root.resolve("dist/trace-agent.jar") + "=" + config,
            "-cp", jar.toString(), "demo.launch.IsolatedTarget", jar.toString()).redirectError(stderr.toFile()).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(target.getOutputStream(), true);
            if (!"ISOLATED_READY".equals(reader.submit(in::readLine).get(15, TimeUnit.SECONDS))) throw new AssertionError("Target ready");
            Properties props = Client.load(config.toString());
            check(Client.send(props, "START").startsWith("OK START"), "START");
            out.println("GO");
            if (!"ISOLATED_DONE".equals(reader.submit(in::readLine).get(15, TimeUnit.SECONDS))) throw new AssertionError("Isolated run");
            String result = Client.send(props, "STOP");
            check(result.contains("events=6 ") && result.contains("saved=6 ") && result.contains("dropped=0 "), result);
            check(Client.send(props, "STATUS").contains("transform_errors=0"), "No transform errors");
            out.println("QUIT");
            check(target.waitFor(10, TimeUnit.SECONDS) && target.exitValue() == 0, "Clean exit");
            String report = "PASS: isolated URLClassLoader(parent=null), bootstrap helper visible; Java=" + java + "\n" + result;
            Files.write(root.resolve("test-results/loader-check-" + (args.length > 1 ? "alternate" : "default") + ".txt"), Collections.singletonList(report), StandardCharsets.UTF_8);
            System.out.println(report);
        } finally { target.destroyForcibly(); reader.shutdownNow(); }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
