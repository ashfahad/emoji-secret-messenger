import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Executors;

public class EmojiStegoWebServer {

    public static final class EmojiStegoCodec {
        private static final int START_MARKER = 0x2062; // INVISIBLE TIMES
        private static final int END_MARKER = 0x2063;   // INVISIBLE SEPARATOR
        private static final int VS_BASE = 0xFE00;      // VARIATION SELECTOR-1..16

        public String encode(String secretMessage, String carrierEmoji) {
            if (secretMessage == null || secretMessage.isEmpty()) {
                throw new IllegalArgumentException("Secret message cannot be empty.");
            }
            if (carrierEmoji == null || carrierEmoji.trim().isEmpty()) {
                throw new IllegalArgumentException("Carrier emoji cannot be empty.");
            }

            byte[] utf8Bytes = secretMessage.getBytes(StandardCharsets.UTF_8);
            int checksum = computeChecksum(utf8Bytes);

            StringBuilder out = new StringBuilder();
            out.append(carrierEmoji);
            out.appendCodePoint(START_MARKER);

            for (byte b : utf8Bytes) {
                int val = b & 0xFF;
                appendByteAsVariationSelectors(out, val);
            }
            appendByteAsVariationSelectors(out, checksum);

            out.appendCodePoint(END_MARKER);
            return out.toString();
        }

        public String decode(String encodedText) {
            if (encodedText == null || encodedText.isEmpty()) {
                throw new IllegalArgumentException("Input text is empty.");
            }

            int startIdx = -1;
            int endIdx = -1;

            List<Integer> codePoints = new ArrayList<>();
            for (int i = 0; i < encodedText.length(); ) {
                int cp = encodedText.codePointAt(i);
                i += Character.charCount(cp);
                codePoints.add(cp);
            }

            for (int i = 0; i < codePoints.size(); i++) {
                int cp = codePoints.get(i);
                if (cp == START_MARKER && startIdx == -1) {
                    startIdx = i;
                } else if (cp == END_MARKER && startIdx != -1) {
                    endIdx = i;
                    break;
                }
            }

            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx + 1) {
                throw new IllegalArgumentException("No valid StegoGlyph payload markers found.");
            }

            List<Integer> nibbles = new ArrayList<>();
            for (int i = startIdx + 1; i < endIdx; i++) {
                int cp = codePoints.get(i);
                if (cp >= VS_BASE && cp < VS_BASE + 16) {
                    nibbles.add(cp - VS_BASE);
                } else {
                    throw new IllegalArgumentException("Unexpected non-selector code point inside payload.");
                }
            }

            if (nibbles.size() < 4 || nibbles.size() % 2 != 0) {
                throw new IllegalArgumentException("Corrupted StegoGlyph nibble stream.");
            }

            int totalBytes = nibbles.size() / 2;
            byte[] decodedAll = new byte[totalBytes];
            for (int i = 0; i < totalBytes; i++) {
                int high = nibbles.get(i * 2);
                int low = nibbles.get(i * 2 + 1);
                decodedAll[i] = (byte) ((high << 4) | low);
            }

            int payloadLen = totalBytes - 1;
            byte[] messageBytes = new byte[payloadLen];
            System.arraycopy(decodedAll, 0, messageBytes, 0, payloadLen);
            int expectedChecksum = decodedAll[payloadLen] & 0xFF;

            int actualChecksum = computeChecksum(messageBytes);
            if (actualChecksum != expectedChecksum) {
                throw new IllegalArgumentException("Checksum mismatch: message may be corrupted or tampered.");
            }

            return new String(messageBytes, StandardCharsets.UTF_8);
        }

        public List<String> splitGraphemeClusters(String text) {
            List<String> clusters = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return clusters;
            }

            BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(text);
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                clusters.add(text.substring(start, end));
            }
            return clusters;
        }

        private static void appendByteAsVariationSelectors(StringBuilder out, int value) {
            int highNibble = (value >>> 4) & 0x0F;
            int lowNibble = value & 0x0F;
            out.appendCodePoint(VS_BASE + highNibble);
            out.appendCodePoint(VS_BASE + lowNibble);
        }

        private static int computeChecksum(byte[] data) {
            int xor = 0x5A;
            for (int i = 0; i < data.length; i++) {
                xor ^= (data[i] & 0xFF);
                xor = ((xor << 1) | (xor >>> 7)) & 0xFF;
            }
            return xor;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--cli".equalsIgnoreCase(args[0])) {
            runCli();
            return;
        }

        String portEnv = System.getenv("PORT");
        int port = 8080;
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                port = Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {
                port = 8080;
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        EmojiStegoCodec codec = new EmojiStegoCodec();

        server.createContext("/", new RootHandler());
        server.createContext("/api/encode", new EncodeHandler(codec));
        server.createContext("/api/decode", new DecodeHandler(codec));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("StegoGlyph server listening on port " + port);
    }

    private static void runCli() {
        EmojiStegoCodec codec = new EmojiStegoCodec();
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        System.out.println("=== StegoGlyph Console CLI ===");
        System.out.println("Commands: encode | decode | exit");
        while (scanner.hasNextLine()) {
            String cmd = scanner.nextLine().trim().toLowerCase();
            if ("exit".equals(cmd)) {
                break;
            } else if ("encode".equals(cmd)) {
                System.out.print("Carrier emoji: ");
                String carrier = scanner.hasNextLine() ? scanner.nextLine() : "";
                System.out.print("Secret message: ");
                String secret = scanner.hasNextLine() ? scanner.nextLine() : "";
                try {
                    System.out.println("Encoded: " + codec.encode(secret, carrier));
                } catch (Exception ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            } else if ("decode".equals(cmd)) {
                System.out.print("Encoded text: ");
                String encoded = scanner.hasNextLine() ? scanner.nextLine() : "";
                try {
                    System.out.println("Decoded: " + codec.decode(encoded));
                } catch (Exception ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            } else {
                System.out.println("Unknown command. Use: encode | decode | exit");
            }
        }
        scanner.close();
    }

    private static final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            byte[] body = STEGO_GLYPH_HTML.getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=UTF-8");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : body.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                exchange.close();
            }
        }
    }

    private static final class EncodeHandler implements HttpHandler {
        private final EmojiStegoCodec codec;

        EncodeHandler(EmojiStegoCodec codec) {
            this.codec = codec;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"ok\":false,\"error\":\"Use POST /api/encode\"}");
                return;
            }
            String raw = readBody(exchange.getRequestBody());
            String secret = extractJsonString(raw, "secret");
            String carrier = extractJsonString(raw, "carrier");
            try {
                String encoded = codec.encode(secret, carrier);
                sendJson(exchange, 200, "{\"ok\":true,\"encoded\":\"" + escapeJson(encoded) + "\"}");
            } catch (Exception ex) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    private static final class DecodeHandler implements HttpHandler {
        private final EmojiStegoCodec codec;

        DecodeHandler(EmojiStegoCodec codec) {
            this.codec = codec;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"ok\":false,\"error\":\"Use POST /api/decode\"}");
                return;
            }
            String raw = readBody(exchange.getRequestBody());
            String encodedText = extractJsonString(raw, "encoded");
            if (encodedText.isEmpty()) {
                encodedText = extractJsonString(raw, "text");
            }
            try {
                String decoded = codec.decode(encodedText);
                sendJson(exchange, 200, "{\"ok\":true,\"decoded\":\"" + escapeJson(decoded) + "\"}");
            } catch (Exception ex) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=UTF-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) {
            return "";
        }
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1) {
            return "";
        }
        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        if (colonIdx == -1) {
            return "";
        }
        int firstQuote = json.indexOf('"', colonIdx + 1);
        if (firstQuote == -1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append('u');
                            }
                        }
                        break;
                    default: sb.append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static final String STEGO_GLYPH_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>StegoGlyph // Variation-Selector Cyber Terminal</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet" />
  <style>
    :root {
      --bg-void: #050806;
      --bg-panel: #0a100c;
      --bg-input: #070b08;
      --bg-elevated: #101913;
      --border-dim: #182b20;
      --border-green: rgba(0, 255, 127, 0.34);
      --border-strong: rgba(0, 255, 127, 0.65);
      --green: #00ff7f;
      --green-soft: #22c55e;
      --green-glow: rgba(0, 255, 127, 0.14);
      --white: #f4f7f5;
      --white-muted: #c2cfc7;
      --dim: #7b8d82;
      --danger: #ff5c77;
      --font-sans: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
      --font-mono: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
      --fs-hero: clamp(1.35rem, 2.4vw, 1.85rem);
      --fs-h2: clamp(0.95rem, 1.3vw, 1.125rem);
      --fs-body: 0.9375rem;
      --fs-ui: 0.875rem;
      --fs-small: 0.78125rem;
      --radius: 10px;
    }

    *, *::before, *::after {
      box-sizing: border-box;
    }

    html, body {
      margin: 0;
      padding: 0;
      min-height: 100%;
      background-color: var(--bg-void);
      background-image:
        radial-gradient(circle at 15% 0%, rgba(0, 255, 127, 0.07), transparent 45%),
        linear-gradient(to bottom, rgba(0, 255, 127, 0.025) 1px, transparent 1px),
        linear-gradient(to right, rgba(0, 255, 127, 0.025) 1px, transparent 1px);
      background-size: 100% 100%, 28px 28px, 28px 28px;
      color: var(--white);
      font-family: var(--font-sans);
      font-size: var(--fs-body);
      line-height: 1.55;
      -webkit-font-smoothing: antialiased;
    }

    .app-shell {
      width: 100%;
      max-width: 1240px;
      margin: 0 auto;
      padding: 1rem 1rem 2.5rem;
    }

    @media (min-width: 768px) {
      .app-shell {
        padding: 1.5rem 1.5rem 3rem;
      }
    }

    .topbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem 1.25rem;
      padding: 0.95rem 1.15rem;
      background: var(--bg-panel);
      border: 1px solid var(--border-green);
      border-radius: var(--radius);
      box-shadow: 0 0 28px rgba(0, 255, 127, 0.06);
      margin-bottom: 1.25rem;
    }

    .brand-group {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .brand-row {
      display: flex;
      align-items: center;
      gap: 0.6rem;
    }

    .brand-title {
      margin: 0;
      font-family: var(--font-mono);
      font-size: var(--fs-hero);
      font-weight: 700;
      letter-spacing: -0.02em;
      color: var(--white);
      line-height: 1.15;
    }

    .brand-title span {
      color: var(--green);
    }

    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.2rem 0.55rem;
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      font-weight: 600;
      color: var(--green);
      background: var(--green-glow);
      border: 1px solid var(--border-green);
      border-radius: 999px;
      line-height: 1.2;
    }

    .status-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--green);
      box-shadow: 0 0 8px var(--green);
    }

    .brand-sub {
      margin: 0;
      font-size: var(--fs-small);
      color: var(--white-muted);
    }

    .mode-switch {
      display: inline-flex;
      align-items: center;
      background: var(--bg-input);
      border: 1px solid var(--border-dim);
      border-radius: 8px;
      padding: 0.2rem;
      gap: 0.2rem;
    }

    .mode-btn {
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      font-weight: 600;
      padding: 0.45rem 0.8rem;
      border: 1px solid transparent;
      border-radius: 6px;
      background: transparent;
      color: var(--white-muted);
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .mode-btn.active {
      background: var(--green-glow);
      color: var(--green);
      border-color: var(--border-green);
    }

    .main-grid {
      display: grid;
      grid-template-columns: 1fr;
      gap: 1.25rem;
      align-items: start;
    }

    @media (min-width: 1024px) {
      .main-grid {
        grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.85fr);
      }
    }

    .codec-stack {
      display: grid;
      grid-template-columns: 1fr;
      gap: 1.25rem;
    }

    .panel {
      background: var(--bg-panel);
      border: 1px solid var(--border-dim);
      border-radius: var(--radius);
      padding: 1.15rem;
    }

    .panel:focus-within {
      border-color: var(--border-green);
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      margin-bottom: 0.9rem;
      padding-bottom: 0.65rem;
      border-bottom: 1px solid var(--border-dim);
    }

    .panel-title {
      margin: 0;
      font-family: var(--font-mono);
      font-size: var(--fs-h2);
      font-weight: 600;
      color: var(--green);
      letter-spacing: 0.01em;
    }

    .panel-tag {
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      color: var(--white-muted);
    }

    .field-group {
      margin-bottom: 0.9rem;
    }

    .field-label-row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
      margin-bottom: 0.35rem;
    }

    label {
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      font-weight: 600;
      color: var(--white);
    }

    .hint-note {
      font-size: var(--fs-small);
      color: var(--dim);
      font-family: var(--font-mono);
    }

    input[type="text"],
    textarea {
      width: 100%;
      background: var(--bg-input);
      color: var(--white);
      border: 1px solid var(--border-dim);
      border-radius: 8px;
      padding: 0.65rem 0.75rem;
      font-family: var(--font-mono);
      font-size: var(--fs-ui);
      line-height: 1.5;
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }

    input[type="text"]::placeholder,
    textarea::placeholder {
      color: var(--dim);
      opacity: 1;
    }

    input[type="text"]:focus,
    textarea:focus {
      outline: none;
      border-color: var(--green);
      box-shadow: 0 0 0 2px rgba(0, 255, 127, 0.14);
    }

    textarea {
      min-height: 92px;
      resize: vertical;
    }

    .meta-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      margin-top: 0.35rem;
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      color: var(--white-muted);
    }

    .action-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      margin-top: 0.85rem;
    }

    .btn {
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      font-weight: 600;
      padding: 0.55rem 0.9rem;
      border-radius: 7px;
      border: 1px solid var(--border-dim);
      background: var(--bg-elevated);
      color: var(--white);
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .btn:hover {
      border-color: var(--border-green);
    }

    .btn-primary {
      background: var(--green);
      color: #041109;
      border-color: var(--green);
    }

    .btn-primary:hover {
      background: #33ff99;
      border-color: #33ff99;
    }

    .output-box {
      margin-top: 0.9rem;
      padding: 0.75rem 0.85rem;
      background: var(--bg-input);
      border: 1px solid var(--border-green);
      border-radius: 8px;
    }

    .output-box.error {
      border-color: rgba(255, 92, 119, 0.7);
    }

    .output-label {
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      color: var(--green);
      margin-bottom: 0.35rem;
    }

    .output-box.error .output-label {
      color: var(--danger);
    }

    .output-value {
      font-family: var(--font-mono);
      font-size: var(--fs-ui);
      color: var(--white);
      word-break: break-word;
      white-space: pre-wrap;
      min-height: 1.4em;
    }

    .ai-panel {
      display: flex;
      flex-direction: column;
      gap: 0.85rem;
    }

    .chat-log {
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
      max-height: 360px;
      overflow-y: auto;
      padding: 0.75rem;
      background: var(--bg-input);
      border: 1px solid var(--border-dim);
      border-radius: 8px;
    }

    .msg {
      padding: 0.6rem 0.75rem;
      border-radius: 8px;
      font-size: var(--fs-small);
      line-height: 1.5;
      white-space: pre-wrap;
    }

    .msg.bot {
      background: var(--bg-elevated);
      border: 1px solid var(--border-dim);
      color: var(--white);
    }

    .msg.bot strong {
      color: var(--green);
      font-family: var(--font-mono);
    }

    .msg.user {
      background: rgba(0, 255, 127, 0.08);
      border: 1px solid var(--border-green);
      color: var(--green);
      font-family: var(--font-mono);
    }

    .chat-form {
      display: flex;
      gap: 0.5rem;
    }

    .chat-form input {
      flex: 1;
    }

    .json-pre {
      margin: 0;
      padding: 0.7rem;
      background: var(--bg-input);
      border: 1px solid var(--border-dim);
      border-radius: 8px;
      font-family: var(--font-mono);
      font-size: var(--fs-small);
      color: var(--green-soft);
      overflow-x: auto;
      white-space: pre-wrap;
    }
  </style>
</head>
<body>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-group">
        <div class="brand-row">
          <h1 class="brand-title">STEGO<span>GLYPH</span></h1>
          <span class="status-pill"><span class="status-dot"></span>U+FE00..FE0F ACTIVE</span>
        </div>
        <p class="brand-sub">Unicode variation-selector steganography with XOR checksum verification</p>
      </div>
      <div class="mode-switch" role="group" aria-label="Execution mode">
        <button type="button" class="mode-btn active" id="modeLocalBtn">LOCAL ENGINE</button>
        <button type="button" class="mode-btn" id="modeApiBtn">SERVER /API</button>
      </div>
    </header>

    <main class="main-grid">
      <div class="codec-stack">
        <section class="panel" aria-labelledby="encodeHeading">
          <div class="panel-header">
            <h2 class="panel-title" id="encodeHeading">// 01. ENCODE PAYLOAD</h2>
            <span class="panel-tag">CARRIER + MARKERS + XOR</span>
          </div>

          <div class="field-group">
            <div class="field-label-row">
              <label for="secretInput">SECRET MESSAGE</label>
              <span class="hint-note">Example: Hi Khalid, we will go tomorrow. 🚀</span>
            </div>
            <textarea
              id="secretInput"
              placeholder="Hi Khalid, we will go tomorrow. 🚀"
              autocomplete="off"
            ></textarea>
            <div class="meta-bar">
              <span id="secretStats">0 UTF-8 bytes · 0 code points</span>
              <span class="hint-note">Clears example hint on focus</span>
            </div>
          </div>

          <div class="field-group">
            <div class="field-label-row">
              <label for="carrierInput">CARRIER EMOJI</label>
              <span class="hint-note">Example: ✈️😛⁢⁣</span>
            </div>
            <input
              type="text"
              id="carrierInput"
              placeholder="✈️😛⁢⁣"
              autocomplete="off"
            />
          </div>

          <div class="action-row">
            <button type="button" class="btn btn-primary" id="encodeBtn">ENCODE GLYPH</button>
            <button type="button" class="btn" id="copyEncodedBtn">COPY OUTPUT</button>
            <button type="button" class="btn" id="sendToDecodeBtn">SEND TO DECODE</button>
            <button type="button" class="btn" id="clearEncodeBtn">CLEAR</button>
          </div>

          <div class="output-box" id="encodeOutputBox">
            <div class="output-label" id="encodeOutputLabel">ENCODED CARRIER STRING</div>
            <div class="output-value" id="encodeOutputValue">Awaiting payload...</div>
          </div>
        </section>

        <section class="panel" aria-labelledby="decodeHeading">
          <div class="panel-header">
            <h2 class="panel-title" id="decodeHeading">// 02. DECODE &amp; VERIFY</h2>
            <span class="panel-tag">CHECKSUM GUARD</span>
          </div>

          <div class="field-group">
            <div class="field-label-row">
              <label for="decodeInput">ENCODED CARRIER INPUT</label>
              <span class="hint-note">Example: ✈️😛⁢⁣</span>
            </div>
            <textarea
              id="decodeInput"
              placeholder="✈️😛⁢⁣"
              autocomplete="off"
            ></textarea>
          </div>

          <div class="action-row">
            <button type="button" class="btn btn-primary" id="decodeBtn">DECODE &amp; VERIFY</button>
            <button type="button" class="btn" id="copyDecodedBtn">COPY SECRET</button>
            <button type="button" class="btn" id="clearDecodeBtn">CLEAR</button>
          </div>

          <div class="output-box" id="decodeOutputBox">
            <div class="output-label" id="decodeOutputLabel">RECOVERED MESSAGE</div>
            <div class="output-value" id="decodeOutputValue">Awaiting carrier string...</div>
          </div>
        </section>
      </div>

      <aside class="panel ai-panel" aria-labelledby="aiHeading">
        <div class="panel-header">
          <h2 class="panel-title" id="aiHeading">// 03. STEGO-GLYPH AI</h2>
          <button type="button" class="btn" id="resetAiBtn">RESET</button>
        </div>

        <div class="chat-log" id="aiChatLog">
          <div class="msg bot">
            <strong>STEGO-AI:</strong> Ask how variation selectors (<code>U+FE00</code>–<code>U+FE0F</code>), framing markers (<code>U+2062</code> / <code>U+2063</code>), XOR checksums, or the <code>/api/encode</code> and <code>/api/decode</code> endpoints work.
          </div>
        </div>

        <form class="chat-form" id="aiChatForm">
          <input
            type="text"
            id="aiPromptInput"
            placeholder="Ask about nibbles, checksum, or API..."
            autocomplete="off"
          />
          <button type="submit" class="btn btn-primary">ASK</button>
        </form>

        <div class="panel-header" style="margin-top: 0.25rem; margin-bottom: 0.4rem; padding-bottom: 0.45rem;">
          <span class="panel-title" style="font-size: var(--fs-small);">// TELEMETRY / JSON</span>
          <span class="panel-tag" id="engineBadge">ENGINE: LOCAL</span>
        </div>
        <pre class="json-pre" id="telemetryJson">{
  "codec": "Unicode Variation Selectors (U+FE00..U+FE0F)",
  "markers": ["U+2062", "U+2063"],
  "checksum": "8-bit rotated XOR (seed 0x5A)"
}</pre>
      </aside>
    </main>
  </div>

  <script>
    (function () {
      const START_MARKER = 0x2062;
      const END_MARKER = 0x2063;
      const VS_BASE = 0xFE00;

      const encoder = new TextEncoder();
      const decoder = new TextDecoder("utf-8", { fatal: true });

      let useServerApi = false;
      let lastEncodedValue = "";
      let lastDecodedValue = "";

      const secretInput = document.getElementById("secretInput");
      const carrierInput = document.getElementById("carrierInput");
      const decodeInput = document.getElementById("decodeInput");
      const secretStats = document.getElementById("secretStats");

      const encodeOutputBox = document.getElementById("encodeOutputBox");
      const encodeOutputLabel = document.getElementById("encodeOutputLabel");
      const encodeOutputValue = document.getElementById("encodeOutputValue");

      const decodeOutputBox = document.getElementById("decodeOutputBox");
      const decodeOutputLabel = document.getElementById("decodeOutputLabel");
      const decodeOutputValue = document.getElementById("decodeOutputValue");

      const telemetryJson = document.getElementById("telemetryJson");
      const engineBadge = document.getElementById("engineBadge");
      const modeLocalBtn = document.getElementById("modeLocalBtn");
      const modeApiBtn = document.getElementById("modeApiBtn");

      [secretInput, carrierInput, decodeInput].forEach((el) => {
        el.addEventListener("focus", () => {
          if (el.value === el.placeholder) {
            el.value = "";
          }
          updateStats();
        });
      });

      function updateStats() {
        const text = secretInput.value || "";
        const bytes = encoder.encode(text).length;
        const cps = Array.from(text).length;
        secretStats.textContent = `${bytes} UTF-8 bytes · ${cps} code points`;
      }

      secretInput.addEventListener("input", updateStats);
      updateStats();

      function computeChecksum(bytes) {
        let xor = 0x5A;
        for (let i = 0; i < bytes.length; i++) {
          xor ^= (bytes[i] & 0xFF);
          xor = ((xor << 1) | (xor >>> 7)) & 0xFF;
        }
        return xor;
      }

      function appendByteAsSelectors(out, byteVal) {
        const high = (byteVal >>> 4) & 0x0F;
        const low = byteVal & 0x0F;
        out.push(String.fromCodePoint(VS_BASE + high));
        out.push(String.fromCodePoint(VS_BASE + low));
      }

      function localEncode(secret, carrier) {
        if (!secret) {
          throw new Error("Secret message cannot be empty.");
        }
        if (!carrier || !carrier.trim()) {
          throw new Error("Carrier emoji cannot be empty.");
        }
        const utf8 = encoder.encode(secret);
        const checksum = computeChecksum(utf8);
        const parts = [carrier, String.fromCodePoint(START_MARKER)];
        for (let i = 0; i < utf8.length; i++) {
          appendByteAsSelectors(parts, utf8[i]);
        }
        appendByteAsSelectors(parts, checksum);
        parts.push(String.fromCodePoint(END_MARKER));
        return parts.join("");
      }

      function localDecode(encodedText) {
        if (!encodedText) {
          throw new Error("Input text is empty.");
        }
        const cps = Array.from(encodedText).map((ch) => ch.codePointAt(0));
        let startIdx = -1;
        let endIdx = -1;

        for (let i = 0; i < cps.length; i++) {
          if (cps[i] === START_MARKER && startIdx === -1) {
            startIdx = i;
          } else if (cps[i] === END_MARKER && startIdx !== -1) {
            endIdx = i;
            break;
          }
        }

        if (startIdx === -1 || endIdx === -1 || endIdx <= startIdx + 1) {
          throw new Error("No valid StegoGlyph payload markers found.");
        }

        const nibbles = [];
        for (let i = startIdx + 1; i < endIdx; i++) {
          const cp = cps[i];
          if (cp >= VS_BASE && cp < VS_BASE + 16) {
            nibbles.push(cp - VS_BASE);
          } else {
            throw new Error("Unexpected non-selector code point inside payload.");
          }
        }

        if (nibbles.length < 4 || nibbles.length % 2 !== 0) {
          throw new Error("Corrupted StegoGlyph nibble stream.");
        }

        const totalBytes = nibbles.length / 2;
        const allBytes = new Uint8Array(totalBytes);
        for (let i = 0; i < totalBytes; i++) {
          allBytes[i] = ((nibbles[i * 2] << 4) | nibbles[i * 2 + 1]) & 0xFF;
        }

        const msgLen = totalBytes - 1;
        const msgBytes = allBytes.slice(0, msgLen);
        const expectedChecksum = allBytes[msgLen];
        const actualChecksum = computeChecksum(msgBytes);

        if (actualChecksum !== expectedChecksum) {
          throw new Error("Checksum mismatch: message may be corrupted or tampered.");
        }

        return decoder.decode(msgBytes);
      }

      function setEncodeResult(ok, text) {
        encodeOutputBox.classList.toggle("error", !ok);
        encodeOutputLabel.textContent = ok ? "ENCODED CARRIER STRING" : "ENCODE ERROR";
        encodeOutputValue.textContent = text;
        if (ok) {
          lastEncodedValue = text;
        }
      }

      function setDecodeResult(ok, text) {
        decodeOutputBox.classList.toggle("error", !ok);
        decodeOutputLabel.textContent = ok ? "RECOVERED MESSAGE" : "DECODE ERROR";
        decodeOutputValue.textContent = text;
        if (ok) {
          lastDecodedValue = text;
        }
      }

      function setMode(serverMode) {
        useServerApi = serverMode;
        modeLocalBtn.classList.toggle("active", !useServerApi);
        modeApiBtn.classList.toggle("active", useServerApi);
        engineBadge.textContent = useServerApi ? "ENGINE: /API" : "ENGINE: LOCAL";
      }

      modeLocalBtn.addEventListener("click", () => setMode(false));
      modeApiBtn.addEventListener("click", () => setMode(true));

      document.getElementById("encodeBtn").addEventListener("click", async () => {
        const secret = secretInput.value;
        const carrier = carrierInput.value;
        try {
          if (useServerApi) {
            const res = await fetch("/api/encode", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ secret, carrier })
            });
            const data = await res.json();
            telemetryJson.textContent = JSON.stringify(data, null, 2);
            if (!res.ok || !data.ok) {
              throw new Error(data.error || "Server encode failed.");
            }
            setEncodeResult(true, data.encoded);
          } else {
            const encoded = localEncode(secret, carrier);
            setEncodeResult(true, encoded);
            telemetryJson.textContent = JSON.stringify({
              ok: true,
              mode: "local",
              codePoints: Array.from(encoded).length,
              utf8Bytes: encoder.encode(secret).length
            }, null, 2);
          }
        } catch (err) {
          setEncodeResult(false, err.message || String(err));
          telemetryJson.textContent = JSON.stringify({ ok: false, error: err.message || String(err) }, null, 2);
        }
      });

      document.getElementById("decodeBtn").addEventListener("click", async () => {
        const encodedText = decodeInput.value;
        try {
          if (useServerApi) {
            const res = await fetch("/api/decode", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ encoded: encodedText })
            });
            const data = await res.json();
            telemetryJson.textContent = JSON.stringify(data, null, 2);
            if (!res.ok || !data.ok) {
              throw new Error(data.error || "Server decode failed.");
            }
            setDecodeResult(true, data.decoded);
          } else {
            const decoded = localDecode(encodedText);
            setDecodeResult(true, decoded);
            telemetryJson.textContent = JSON.stringify({
              ok: true,
              mode: "local",
              decodedCodePoints: Array.from(decoded).length
            }, null, 2);
          }
        } catch (err) {
          setDecodeResult(false, err.message || String(err));
          telemetryJson.textContent = JSON.stringify({ ok: false, error: err.message || String(err) }, null, 2);
        }
      });

      document.getElementById("sendToDecodeBtn").addEventListener("click", () => {
        if (lastEncodedValue) {
          decodeInput.value = lastEncodedValue;
          decodeInput.focus();
        }
      });

      document.getElementById("copyEncodedBtn").addEventListener("click", () => {
        if (lastEncodedValue && navigator.clipboard) {
          navigator.clipboard.writeText(lastEncodedValue).catch(() => {});
        }
      });

      document.getElementById("copyDecodedBtn").addEventListener("click", () => {
        if (lastDecodedValue && navigator.clipboard) {
          navigator.clipboard.writeText(lastDecodedValue).catch(() => {});
        }
      });

      document.getElementById("clearEncodeBtn").addEventListener("click", () => {
        secretInput.value = "";
        carrierInput.value = "";
        lastEncodedValue = "";
        encodeOutputBox.classList.remove("error");
        encodeOutputLabel.textContent = "ENCODED CARRIER STRING";
        encodeOutputValue.textContent = "Awaiting payload...";
        updateStats();
      });

      document.getElementById("clearDecodeBtn").addEventListener("click", () => {
        decodeInput.value = "";
        lastDecodedValue = "";
        decodeOutputBox.classList.remove("error");
        decodeOutputLabel.textContent = "RECOVERED MESSAGE";
        decodeOutputValue.textContent = "Awaiting carrier string...";
      });

      const aiChatLog = document.getElementById("aiChatLog");
      const aiChatForm = document.getElementById("aiChatForm");
      const aiPromptInput = document.getElementById("aiPromptInput");
      const resetAiBtn = document.getElementById("resetAiBtn");

      function appendChat(role, text) {
        const div = document.createElement("div");
        div.className = "msg " + role;
        if (role === "bot") {
          const strong = document.createElement("strong");
          strong.textContent = "STEGO-AI: ";
          div.appendChild(strong);
          div.appendChild(document.createTextNode(text));
        } else {
          div.textContent = "> " + text;
        }
        aiChatLog.appendChild(div);
        aiChatLog.scrollTop = aiChatLog.scrollHeight;
      }

      function answerStegoAi(prompt) {
        const q = prompt.toLowerCase();
        if (q.includes("checksum") || q.includes("xor") || q.includes("tamper")) {
          return "Each UTF-8 byte is split into two 4-bit nibbles mapped to U+FE00..U+FE0F, followed by an 8-bit rotated XOR checksum (seed 0x5A) before U+2063. Flipping a payload selector breaks the checksum and throws an error.";
        }
        if (q.includes("marker") || q.includes("2062") || q.includes("2063") || q.includes("invisible")) {
          return "U+2062 marks the start of the hidden variation-selector stream and U+2063 marks the end, keeping the visible carrier emoji intact.";
        }
        if (q.includes("api") || q.includes("server") || q.includes("render") || q.includes("endpoint")) {
          return "POST /api/encode accepts {\"secret\":\"...\",\"carrier\":\"...\"} and POST /api/decode accepts {\"encoded\":\"...\"}. Toggle SERVER /API at the top to test live backend calls.";
        }
        return "StegoGlyph encodes UTF-8 bytes as pairs of Unicode variation selectors (U+FE00..U+FE0F) between U+2062 and U+2063 with an XOR checksum. Ask about checksums, markers, or the /api endpoints.";
      }

      aiChatForm.addEventListener("submit", (e) => {
        e.preventDefault();
        const prompt = aiPromptInput.value.trim();
        if (!prompt) return;
        appendChat("user", prompt);
        appendChat("bot", answerStegoAi(prompt));
        aiPromptInput.value = "";
      });

      resetAiBtn.addEventListener("click", () => {
        aiChatLog.innerHTML = "";
        appendChat("bot", "Session reset. Ask about U+FE00..U+FE0F variation selectors, U+2062/U+2063 markers, XOR checksums, or /api endpoints.");
      });
    })();
  </script>
</body>
</html>
""";
}
