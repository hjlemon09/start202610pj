package execute;

import com.CallTreeExtractor;

/**
 * Entry point of staticAnalyzer when compiled (build.cmd / run.cmd). Arguments are those of CallTreeExtractor.
 * The single-file launch {@code java -cp javaparser-core.jar com/CallTreeExtractor.java} still works unchanged.
 */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) throws Exception {
        CallTreeExtractor.main(args);
    }
}
