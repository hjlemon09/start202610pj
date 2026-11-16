package trace.control;

import java.awt.*;
import java.util.*;
import javax.swing.*;

public final class ControlWindow {
    public static void main(String[] args) throws Exception {
        final Properties props = Client.load(args.length == 0 ? "config/demo.properties" : args[0]);
        SwingUtilities.invokeLater(() -> show(props));
    }
    private static void show(Properties props) {
        JFrame frame = new JFrame("Commerce Call Tracer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JTextArea log = new JTextArea(18, 100); log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        JTextField thread = new JTextField("0", 8);
        JCheckBox discard = new JCheckBox("Discard (no CSV)");
        JPanel bar = new JPanel();
        bar.add(new JLabel("Thread ID (0 = all):")); bar.add(thread); bar.add(discard);
        java.util.List<JButton> buttons = new ArrayList<JButton>();
        for (String action : new String[]{"STATUS", "START", "STOP"}) {
            JButton button = new JButton(action); buttons.add(button); bar.add(button);
            button.addActionListener(event -> {
                String cmd = action;
                if ("START".equals(action)) {
                    try { if (Long.parseLong(thread.getText().trim()) < 0) throw new NumberFormatException(); }
                    catch (NumberFormatException ex) { log.append("Thread ID must be >= 0.\n"); return; }
                    cmd += " " + thread.getText().trim() + (discard.isSelected() ? " DISCARD" : " SAVE");
                }
                final String command = cmd;
                for (JButton b : buttons) b.setEnabled(false);
                log.append(command + " ...\n");
                new SwingWorker<String, Void>() {
                    protected String doInBackground() throws Exception { return Client.send(props, command); }
                    protected void done() {
                        try { log.append(get() + "\n\n"); }
                        catch (Exception ex) { log.append("ERROR " + ex.getMessage() + "\n\n"); }
                        for (JButton b : buttons) b.setEnabled(true);
                        log.setCaretPosition(log.getDocument().getLength());
                    }
                }.execute();
            });
        }
        frame.add(bar, BorderLayout.NORTH); frame.add(new JScrollPane(log)); frame.pack(); frame.setLocationByPlatform(true); frame.setVisible(true);
        log.append("Agent: 127.0.0.1:" + props.getProperty("port", "43127") + "\nSet BOTH breakpoints to Suspend: Thread.\n");
    }
}
