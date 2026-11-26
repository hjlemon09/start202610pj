package execute;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.javatool.search.JarCodeSearch;
import com.javatool.search.SearchWindow;

/** Entry point of jar-code-search. No arguments: search window. Otherwise: command line search. */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { SearchWindow.open(); return; }
        if (args.length < 2 || args[0].equals("--help")) {
            System.out.println("Usage: run.cmd <folder> <keyword> [--regex] [--ignore-case] [--charset=MS949]\nNo arguments: open search window."); return;
        }
        boolean regex = false, ignore = false;
        Charset charset = StandardCharsets.UTF_8;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--regex")) regex = true;
            else if (args[i].equals("--ignore-case")) ignore = true;
            else if (args[i].startsWith("--charset=")) charset = Charset.forName(args[i].substring(10));
            else throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
        new JarCodeSearch(new JarCodeSearch.Options(Path.of(args[0]), args[1], regex, ignore, charset,
                JarCodeSearch.newOutput()), System.out::println).run();
    }
}
