package demo.shop;

public class Demo {
    private static volatile int result;
    public static void main(String[] args) throws Exception {
        int rounds = args.length == 0 ? 2 : Integer.parseInt(args[0]);
        for (int i = 0; i < rounds; i++) outer(i);
        System.out.println("DEMO_DONE " + result);
    }
    static void outer(int round) throws Exception {
        before();
        startBoundary(round); // START_BREAKPOINT: stop BEFORE this call; START then Resume.
        facade();
        try { failingService(); } catch (IllegalStateException expected) { result++; }
        recursive(2);
        new Thread(() -> background(), "other-request").start();
        stopBoundary(round); // STOP_BREAKPOINT: stop BEFORE this call; STOP then Resume.
        after();
    }
    static void startBoundary(int round) { result += round; }
    static void stopBoundary(int round) { result += round; }
    static void before() { result++; }
    static void after() { result++; }
    static void facade() { service(); }
    static void service() { dao(); }
    static void dao() { result++; }
    static void failingService() { throw new IllegalStateException("expected demo exception"); }
    static void recursive(int n) { if (n > 0) recursive(n - 1); }
    static void background() { dao(); }
}
