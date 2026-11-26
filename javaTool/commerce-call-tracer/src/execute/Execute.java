package execute;

import java.util.Arrays;

import trace.control.Client;
import trace.control.ControlWindow;

/**
 * Entry point of the commerce-call-tracer controller (Java 8 compatible, packaged in trace-controller.jar).
 * {@code Execute [config.properties]}: opens the control window.
 * {@code Execute config.properties STATUS|START|STOP [thread-id] [DISCARD]}: sends one command and prints the reply.
 */
public final class Execute {
    private Execute() {
    }

    public static void main(String[] args) throws Exception {
        String config = args.length == 0 ? "config/demo.properties" : args[0];
        if (args.length <= 1) {
            ControlWindow.open(Client.load(config));
            return;
        }
        String reply = Client.send(Client.load(config), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        System.out.println(reply);
        if (!reply.startsWith("OK ")) System.exit(2);
    }
}
