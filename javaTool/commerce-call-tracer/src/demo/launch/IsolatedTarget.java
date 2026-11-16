package demo.launch;

import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Child loader with no system-classloader parent, approximating container isolation. */
public final class IsolatedTarget {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ISOLATED_READY"); System.out.flush();
        in.readLine();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Paths.get(args[0]).toUri().toURL()}, null)) {
            Class<?> type = Class.forName("demo.shop.Demo", true, loader);
            java.lang.reflect.Method method = type.getDeclaredMethod("facade"); method.setAccessible(true); method.invoke(null);
        }
        System.out.println("ISOLATED_DONE"); System.out.flush();
        in.readLine();
    }
}
