package trace.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;
import net.bytebuddy.matcher.ElementMatcher;
import trace.runtime.Recorder;

public final class Agent {
    public static void premain(String config, Instrumentation instrumentation) throws Exception {
        Path home = Paths.get(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(home.resolve("trace-runtime.jar").toFile()));
        // Resolve the helper only AFTER appending its JAR to the bootstrap loader.
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get(config), java.nio.charset.StandardCharsets.UTF_8)) { props.load(reader); }
        String[] prefixes = props.getProperty("packages", "").split(",");
        ElementMatcher.Junction<TypeDescription> match = none();
        for (String prefix : prefixes) {
            prefix = prefix.trim();
            if (prefix.isEmpty() || prefix.startsWith("java.") || prefix.startsWith("trace.") || prefix.startsWith("net.bytebuddy."))
                throw new IllegalArgumentException("Use explicit application package prefixes: " + prefix);
            match = match.or(nameStartsWith(prefix.endsWith(".") ? prefix : prefix + "."));
        }
        new AgentBuilder.Default()
            .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("trace.")))
            .with(new AgentBuilder.Listener.Adapter() {
                public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded, DynamicType dynamic) {
                    Recorder.transformed(true);
                }
                public void onError(String name, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
                    Recorder.transformed(false);
                    System.err.println("[call-tracer] instrumentation failed " + name + " " + error);
                }
            })
            .type(match)
            .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(Hook.class)
                .on(isMethod().and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic())))))
            .installOn(instrumentation);
        Recorder.boot(props);
    }
    public static class Hook {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Recorder.Frame enter(@Advice.Origin("#t.#m#d") String method) { return Recorder.enter(method); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Recorder.Frame frame, @Advice.Origin("#t.#m#d") String method,
                                @Advice.Thrown Throwable error) { Recorder.exit(frame, method, error != null); }
    }
}
