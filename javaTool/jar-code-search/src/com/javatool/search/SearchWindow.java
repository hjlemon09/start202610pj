package com.javatool.search;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

public final class SearchWindow {
    private SearchWindow() {}
    public static void open() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("JAR Code Search - Java 21");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            JTextField folder = new JTextField(45), keyword = new JTextField("new PlatformInPlaceClassLoader", 45);
            JTextField encoding = new JTextField("UTF-8", 12);
            JCheckBox regex = new JCheckBox("정규식"), ignore = new JCheckBox("대소문자 무시");
            JTextArea result = new JTextArea(24,100);
            result.setEditable(false);
            JButton browse = new JButton("폴더 선택"), start = new JButton("검색");
            browse.addActionListener(event -> {
                JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) folder.setText(chooser.getSelectedFile().getAbsolutePath());
            });
            JPanel fields = new JPanel(new GridLayout(0,1,4,4));
            JPanel first = new JPanel(new FlowLayout(FlowLayout.LEFT)); first.add(new JLabel("검색 폴더")); first.add(folder); first.add(browse);
            JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT)); second.add(new JLabel("검색어")); second.add(keyword);
            JPanel third = new JPanel(new FlowLayout(FlowLayout.LEFT)); third.add(regex); third.add(ignore); third.add(new JLabel("텍스트 인코딩")); third.add(encoding); third.add(start);
            fields.add(first); fields.add(second); fields.add(third);
            frame.add(fields, BorderLayout.NORTH); frame.add(new JScrollPane(result), BorderLayout.CENTER);
            start.addActionListener(event -> {
                final JarCodeSearch.Options options;
                try {
                    options = new JarCodeSearch.Options(Path.of(folder.getText()), keyword.getText(), regex.isSelected(), ignore.isSelected(), Charset.forName(encoding.getText()), JarCodeSearch.newOutput());
                    new JarCodeSearch(options, value -> {}); // Validate query before starting.
                } catch (Exception ex) { JOptionPane.showMessageDialog(frame, ex.toString()); return; }
                result.setText(""); start.setEnabled(false);
                new SwingWorker<JarCodeSearch.Result,String>() {
                    protected JarCodeSearch.Result doInBackground() throws Exception { return new JarCodeSearch(options, this::publish).run(); }
                    protected void process(List<String> messages) {
                        for (String message : messages) result.append(message + "\n");
                        if (result.getDocument().getLength() > 200000) result.replaceRange("",0,100000);
                        result.setCaretPosition(result.getDocument().getLength());
                    }
                    protected void done() {
                        start.setEnabled(true);
                        try { result.append("Completed: " + get().output() + "\n"); }
                        catch (Exception ex) { result.append("FAILED: " + ex + "\n"); }
                    }
                }.execute();
            });
            frame.pack(); frame.setLocationRelativeTo(null); frame.setVisible(true);
        });
    }
}
