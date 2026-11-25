import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

public class EmojiStegoApp extends JFrame {

    private final EmojiStegoWebServer.EmojiStegoCodec codec =
            new EmojiStegoWebServer.EmojiStegoCodec();

    public EmojiStegoApp() {
        super("Emoji Secret Messenger - Unicode Steganography");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(660, 500);
        setMinimumSize(new Dimension(540, 420));
        setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Encode (Hide Message)", buildEncodePanel());
        tabs.addTab("Decode (Reveal Message)", buildDecodePanel());

        add(tabs, BorderLayout.CENTER);
    }

    private JPanel buildEncodePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        Font textFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        Font emojiFont = createEmojiFont(22);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weighty = 0;
        panel.add(new JLabel("Input-1: Secret Message"), gbc);

        JTextArea secretInput = new JTextArea(4, 30);
        secretInput.setFont(textFont);
        secretInput.setLineWrap(true);
        secretInput.setWrapStyleWord(true);
        secretInput.setText("Hi Khalid, we will go tomorrow.");

        gbc.gridy = 1;
        gbc.weighty = 0.35;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(secretInput), gbc);

        JLabel metricsLabel = new JLabel();
        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(metricsLabel, gbc);

        gbc.gridy = 3;
        panel.add(new JLabel("Input-2: Carrier Emoji (e.g., ✈️😛 or 👩‍💻)"), gbc);

        JTextField emojiInput = new JTextField("✈️😛");
        emojiInput.setFont(emojiFont);
        gbc.gridy = 4;
        panel.add(emojiInput, gbc);

        JButton encodeBtn = new JButton("Encode Message");
        JButton copyBtn = new JButton("Copy Output");
        JButton loadBtn = new JButton("Load Message (.txt)");
        JButton clearBtn = new JButton("Clear");

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.add(encodeBtn);
        actionRow.add(copyBtn);
        actionRow.add(loadBtn);
        actionRow.add(clearBtn);

        gbc.gridy = 5;
        panel.add(actionRow, gbc);

        gbc.gridy = 6;
        panel.add(new JLabel("Output (Looks like your emoji—paste into WhatsApp, Messenger, SMS, etc.):"), gbc);

        JTextArea encodedOutput = new JTextArea(2, 30);
        encodedOutput.setFont(emojiFont);
        encodedOutput.setEditable(false);
        encodedOutput.setLineWrap(true);
        encodedOutput.setWrapStyleWord(true);

        gbc.gridy = 7;
        gbc.weighty = 0.25;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(encodedOutput), gbc);

        JLabel statusLabel = new JLabel("Ready. Encode a message, then click Copy Output.");
        gbc.gridy = 8;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(statusLabel, gbc);

        Runnable updateMetrics = new Runnable() {
            @Override
            public void run() {
                String msg = secretInput.getText();
                String carrier = emojiInput.getText();
                int utf8Bytes = msg.getBytes(StandardCharsets.UTF_8).length;
                int carrierClusters = codec.splitGraphemeClusters(carrier).size();
                int hiddenCodePoints = msg.isEmpty() ? 0 : (utf8Bytes * 2) + 4;
                int totalCodePoints = carrierClusters + hiddenCodePoints;
                metricsLabel.setText(String.format(
                        "UTF-8 Size: %d bytes  |  Hidden Code Points: %d  |  Total Output Code Points (~%d carriers): %d",
                        utf8Bytes, hiddenCodePoints, carrierClusters, totalCodePoints
                ));
            }
        };
        updateMetrics.run();

        DocumentListener metricsListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateMetrics.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateMetrics.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateMetrics.run();
            }
        };
        secretInput.getDocument().addDocumentListener(metricsListener);
        emojiInput.getDocument().addDocumentListener(metricsListener);

        encodeBtn.addActionListener(e -> {
            try {
                String result = codec.encode(secretInput.getText(), emojiInput.getText());
                encodedOutput.setText(result);
                statusLabel.setText("Encoded successfully (" + countCodePoints(result) + " total Unicode code points).");
            } catch (IllegalArgumentException ex) {
                statusLabel.setText("Error: " + ex.getMessage());
            }
        });

        copyBtn.addActionListener(e -> {
            String out = encodedOutput.getText();
            if (out == null || out.isEmpty()) {
                statusLabel.setText("Nothing to copy yet—encode a message first.");
                return;
            }
            copyToClipboard(out);
            statusLabel.setText("Copied to clipboard! Paste it anywhere and send.");
        });

        loadBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selected = chooser.getSelectedFile();
                try {
                    String content = readUtf8File(selected);
                    secretInput.setText(content);
                    statusLabel.setText("Loaded message from " + selected.getName() + ".");
                } catch (IOException ex) {
                    statusLabel.setText("Failed to load file: " + ex.getMessage());
                }
            }
        });

        clearBtn.addActionListener(e -> {
            secretInput.setText("");
            encodedOutput.setText("");
            statusLabel.setText("Cleared message and output.");
        });

        return panel;
    }

    private JPanel buildDecodePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        Font textFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        Font emojiFont = createEmojiFont(22);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weighty = 0;
        panel.add(new JLabel("Paste Received Emoji Here:"), gbc);

        JTextArea stegoInput = new JTextArea(2, 30);
        stegoInput.setFont(emojiFont);
        stegoInput.setLineWrap(true);
        stegoInput.setWrapStyleWord(true);

        gbc.gridy = 1;
        gbc.weighty = 0.25;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(stegoInput), gbc);

        JButton pasteAndDecodeBtn = new JButton("Paste & Decode");
        JButton decodeBtn = new JButton("Decode Above");
        JButton saveBtn = new JButton("Save Decoded (.txt)");
        JButton clearBtn = new JButton("Clear");

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.add(pasteAndDecodeBtn);
        actionRow.add(decodeBtn);
        actionRow.add(saveBtn);
        actionRow.add(clearBtn);

        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(actionRow, gbc);

        gbc.gridy = 3;
        panel.add(new JLabel("Hidden Message:"), gbc);

        JTextArea decodedOutput = new JTextArea(5, 30);
        decodedOutput.setFont(textFont);
        decodedOutput.setEditable(false);
        decodedOutput.setLineWrap(true);
        decodedOutput.setWrapStyleWord(true);

        gbc.gridy = 4;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(decodedOutput), gbc);

        JLabel statusLabel = new JLabel("Paste an encoded emoji to reveal the hidden message.");
        gbc.gridy = 5;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(statusLabel, gbc);

        decodeBtn.addActionListener(e -> runDecode(stegoInput.getText(), decodedOutput, statusLabel));

        pasteAndDecodeBtn.addActionListener(e -> {
            try {
                String pasted = readFromClipboard();
                stegoInput.setText(pasted);
                runDecode(pasted, decodedOutput, statusLabel);
            } catch (Exception ex) {
                statusLabel.setText("Clipboard error: " + ex.getMessage());
            }
        });

        saveBtn.addActionListener(e -> {
            String decoded = decodedOutput.getText();
            if (decoded == null || decoded.isEmpty()) {
                statusLabel.setText("Nothing to save yet—decode a message first.");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
            chooser.setSelectedFile(new File("decoded_message.txt"));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File target = chooser.getSelectedFile();
                if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    target = new File(target.getParentFile(), target.getName() + ".txt");
                }
                try {
                    writeUtf8File(target, decoded);
                    statusLabel.setText("Saved decoded message to " + target.getName() + ".");
                } catch (IOException ex) {
                    statusLabel.setText("Failed to save file: " + ex.getMessage());
                }
            }
        });

        clearBtn.addActionListener(e -> {
            stegoInput.setText("");
            decodedOutput.setText("");
            statusLabel.setText("Cleared pasted input and decoded message.");
        });

        return panel;
    }

    private void runDecode(String input, JTextArea outputArea, JLabel statusLabel) {
        try {
            String secret = codec.decode(input);
            outputArea.setText(secret);
            int byteCount = secret.getBytes(StandardCharsets.UTF_8).length;
            statusLabel.setText("Decoded message (" + secret.length() + " chars, " + byteCount + " UTF-8 bytes).");
        } catch (IllegalArgumentException ex) {
            outputArea.setText("");
            statusLabel.setText("Could not decode: " + ex.getMessage());
        }
    }

    private static String readUtf8File(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(line);
                first = false;
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static void writeUtf8File(File file, String content) throws IOException {
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private void copyToClipboard(String text) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
    }

    private String readFromClipboard() throws Exception {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Object data = cb.getData(DataFlavor.stringFlavor);
        return data == null ? "" : data.toString();
    }

    private Font createEmojiFont(int size) {
        String[] preferred = {"Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", Font.SANS_SERIF};
        for (String name : preferred) {
            Font f = new Font(name, Font.PLAIN, size);
            if (!Font.DIALOG.equals(f.getFamily()) || name.equals(Font.SANS_SERIF)) {
                return new Font(name, Font.PLAIN, size);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    private static int countCodePoints(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new EmojiStegoApp().setVisible(true);
            }
        });
    }
}
