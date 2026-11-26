package execute;

import com.CallTreeExtractor;

public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) {
        try {
            CallTreeExtractor.run(args);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }
}
