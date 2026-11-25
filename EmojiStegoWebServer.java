import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public class EmojiStegoWebServer {

    private static final EmojiStegoCodec CODEC = new EmojiStegoCodec();

    public static void main(String[] args) throws IOException {
        if (args != null && args.length > 0 && ("--cli".equalsIgnoreCase(args[0]) || "-c".equalsIgnoreCase(args[0]))) {
            runConsoleMode();
            return;
        }

        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.trim().isEmpty()) ? Integer.parseInt(portEnv.trim()) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new HomeHandler());
        server.createContext("/api/encode", new EncodeHandler());
        server.createContext("/api/decode", new DecodeHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("Emoji Secret Messenger (Java Web Server) running on http://localhost:" + port);
        System.out.println("Tip: Run with '--cli' for console mode: java EmojiStegoWebServer --cli");
    }

    private static void runConsoleMode() {
        Scanner scanner = new Scanner(System.in, "UTF-8");
        System.out.println("=== Emoji Secret Messenger (Console Fallback Mode) ===");

        while (true) {
            System.out.println();
            System.out.println("1) Encode secret message into emoji");
            System.out.println("2) Decode emoji to reveal secret message");
            System.out.println("3) Exit");
            System.out.print("Choose an option (1-3): ");

            if (!scanner.hasNextLine()) {
                break;
            }
            String choice = scanner.nextLine().trim();

            if ("1".equals(choice)) {
                System.out.print("Enter secret message (Input-1): ");
                String message = scanner.nextLine();
                System.out.print("Enter carrier emoji (Input-2, e.g., ✈️😛): ");
                String emoji = scanner.nextLine();

                try {
                    String encoded = CODEC.encode(message, emoji);
                    System.out.println("Encoded Output (copy this and send): " + encoded);
                } catch (IllegalArgumentException ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            } else if ("2".equals(choice)) {
                System.out.print("Paste received emoji string: ");
                String stego = scanner.nextLine();

                try {
                    String decoded = CODEC.decode(stego);
                    System.out.println("Decoded Secret Message: " + decoded);
                } catch (IllegalArgumentException ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            } else if ("3".equals(choice)) {
                System.out.println("Exiting console mode.");
                break;
            } else {
                System.out.println("Invalid option. Please enter 1, 2, or 3.");
            }
        }
        scanner.close();
    }

    private static class HomeHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            sendHtml(exchange, 200, HTML_PAGE);
        }
    }

    private static class EncodeHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> params = parseFormUrlEncoded(readBody(exchange));
            String message = params.containsKey("message") ? params.get("message") : "";
            String emoji = params.containsKey("emoji") ? params.get("emoji") : "";

            try {
                String encoded = CODEC.encode(message, emoji);
                sendJson(exchange, 200, "{\"ok\":true,\"encoded\":" + toJsonString(encoded) + "}");
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":" + toJsonString(ex.getMessage()) + "}");
            }
        }
    }

    private static class DecodeHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> params = parseFormUrlEncoded(readBody(exchange));
            String stego = params.containsKey("stego") ? params.get("stego") : "";

            try {
                String decoded = CODEC.decode(stego);
                sendJson(exchange, 200, "{\"ok\":true,\"decoded\":" + toJsonString(decoded) + "}");
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":" + toJsonString(ex.getMessage()) + "}");
            }
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;
        try {
            while ((bytesRead = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
        } finally {
            is.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseFormUrlEncoded(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                try {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } catch (UnsupportedEncodingException ignored) {
                    // UTF-8 is always supported in Java
                }
            }
        }
        return map;
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    private static String toJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    public static class EmojiStegoCodec {
        private static final int START_MARKER = 0x2062;
        private static final int END_MARKER = 0x2063;
        private static final int NIBBLE_BASE = 0xFE00;

        public String encode(String secretMessage, String carrierEmojiInput) {
            if (secretMessage == null || secretMessage.isEmpty()) {
                throw new IllegalArgumentException("Secret message cannot be empty.");
            }
            List<String> clusters = splitGraphemeClusters(carrierEmojiInput);
            if (clusters.isEmpty()) {
                throw new IllegalArgumentException("Please enter at least one carrier emoji.");
            }

            byte[] payload = secretMessage.getBytes(StandardCharsets.UTF_8);
            int checksum = 0;
            for (byte b : payload) {
                checksum ^= (b & 0xFF);
            }

            StringBuilder sb = new StringBuilder();
            for (String cluster : clusters) {
                sb.append(cluster);
            }

            sb.appendCodePoint(START_MARKER);
            for (byte b : payload) {
                int val = b & 0xFF;
                sb.appendCodePoint(NIBBLE_BASE + ((val >>> 4) & 0x0F));
                sb.appendCodePoint(NIBBLE_BASE + (val & 0x0F));
            }
            sb.appendCodePoint(NIBBLE_BASE + ((checksum >>> 4) & 0x0F));
            sb.appendCodePoint(NIBBLE_BASE + (checksum & 0x0F));
            sb.appendCodePoint(END_MARKER);
            return sb.toString();
        }

        public String decode(String stegoText) {
            if (stegoText == null || stegoText.isEmpty()) {
                throw new IllegalArgumentException("Input is empty.");
            }

            List<Integer> nibbles = new ArrayList<Integer>();
            boolean insidePayload = false;
            boolean foundEnd = false;

            for (int i = 0; i < stegoText.length(); ) {
                int cp = stegoText.codePointAt(i);
                i += Character.charCount(cp);

                if (cp == START_MARKER) {
                    insidePayload = true;
                    nibbles.clear();
                    foundEnd = false;
                } else if (cp == END_MARKER && insidePayload) {
                    foundEnd = true;
                    break;
                } else if (insidePayload && cp >= NIBBLE_BASE && cp <= NIBBLE_BASE + 0x0F) {
                    nibbles.add(cp - NIBBLE_BASE);
                }
            }

            if (!insidePayload || !foundEnd) {
                throw new IllegalArgumentException("No complete hidden message markers found.");
            }
            if (nibbles.size() < 4 || nibbles.size() % 2 != 0) {
                throw new IllegalArgumentException("Hidden payload length is invalid or truncated.");
            }

            int totalBytes = nibbles.size() / 2;
            int messageByteCount = totalBytes - 1;
            byte[] messageBytes = new byte[messageByteCount];

            int computedChecksum = 0;
            for (int bIdx = 0; bIdx < messageByteCount; bIdx++) {
                int value = (nibbles.get(bIdx * 2) << 4) | nibbles.get(bIdx * 2 + 1);
                messageBytes[bIdx] = (byte) value;
                computedChecksum ^= value;
            }

            int expectedChecksum = (nibbles.get(messageByteCount * 2) << 4)
                    | nibbles.get(messageByteCount * 2 + 1);

            if (computedChecksum != expectedChecksum) {
                throw new IllegalArgumentException("Checksum mismatch—data may be altered or stripped.");
            }

            return new String(messageBytes, StandardCharsets.UTF_8);
        }

        public List<String> splitGraphemeClusters(String text) {
            List<String> clusters = new ArrayList<String>();
            if (text == null || text.isEmpty()) {
                return clusters;
            }
            BreakIterator boundary = BreakIterator.getCharacterInstance(Locale.ROOT);
            boundary.setText(text);
            int start = boundary.first();
            for (int end = boundary.next(); end != BreakIterator.DONE; start = end, end = boundary.next()) {
                String chunk = text.substring(start, end).trim();
                if (!chunk.isEmpty()) {
                    clusters.add(chunk);
                }
            }
            return clusters;
        }
    }

    private static final String HTML_PAGE =
            "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\" />\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n"
            + "  <title>Emoji Secret Messenger (Java 8)</title>\n"
            + "  <style>\n"
            + "    body { font-family: system-ui, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; background: #f8fafc; color: #0f172a; }\n"
            + "    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 1.25rem; margin-bottom: 1.25rem; }\n"
            + "    label { display: block; font-weight: 600; margin-top: 0.75rem; margin-bottom: 0.25rem; }\n"
            + "    textarea, input { width: 100%; box-sizing: border-box; padding: 0.65rem; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 1rem; }\n"
            + "    .emoji-box { font-size: 1.5rem; }\n"
            + "    button { margin-top: 0.75rem; margin-right: 0.5rem; padding: 0.55rem 1rem; border: 0; border-radius: 8px; background: #2563eb; color: #fff; font-weight: 600; cursor: pointer; }\n"
            + "    button.secondary { background: #475569; }\n"
            + "    .status { margin-top: 0.6rem; font-size: 0.9rem; color: #334155; }\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <h1>Emoji Secret Messenger</h1>\n"
            + "  <p>Powered by a <strong>Java 8</strong> backend (<code>HttpServer</code> + <code>EmojiStegoCodec</code>).</p>\n"
            + "  <div class=\"card\">\n"
            + "    <h2>1. Encode Message into Emoji</h2>\n"
            + "    <label for=\"secretMsg\">Secret Message</label>\n"
            + "    <textarea id=\"secretMsg\" rows=\"3\">Hi Khalid, we will go tomorrow.</textarea>\n"
            + "    <label for=\"carrierEmoji\">Carrier Emoji</label>\n"
            + "    <input id=\"carrierEmoji\" class=\"emoji-box\" value=\"✈️😛\" />\n"
            + "    <button onclick=\"encodeMsg()\">Encode</button>\n"
            + "    <button class=\"secondary\" onclick=\"copyEncoded()\">Copy Output</button>\n"
            + "    <label for=\"encodedOut\">Encoded Emoji Output</label>\n"
            + "    <textarea id=\"encodedOut\" class=\"emoji-box\" rows=\"2\" readonly></textarea>\n"
            + "    <div id=\"encodeStatus\" class=\"status\"></div>\n"
            + "  </div>\n"
            + "  <div class=\"card\">\n"
            + "    <h2>2. Decode Pasted Emoji</h2>\n"
            + "    <label for=\"stegoIn\">Paste Emoji Here</label>\n"
            + "    <textarea id=\"stegoIn\" class=\"emoji-box\" rows=\"2\"></textarea>\n"
            + "    <button onclick=\"decodeMsg()\">Decode</button>\n"
            + "    <label for=\"decodedOut\">Revealed Message</label>\n"
            + "    <textarea id=\"decodedOut\" rows=\"3\" readonly></textarea>\n"
            + "    <div id=\"decodeStatus\" class=\"status\"></div>\n"
            + "  </div>\n"
            + "  <script>\n"
            + "    async function encodeMsg() {\n"
            + "      const msg = document.getElementById('secretMsg').value;\n"
            + "      const emoji = document.getElementById('carrierEmoji').value;\n"
            + "      const status = document.getElementById('encodeStatus');\n"
            + "      const body = new URLSearchParams({ message: msg, emoji: emoji });\n"
            + "      const res = await fetch('/api/encode', { method: 'POST', body: body });\n"
            + "      const data = await res.json();\n"
            + "      if (data.ok) {\n"
            + "        document.getElementById('encodedOut').value = data.encoded;\n"
            + "        status.textContent = 'Encoded on Java server.';\n"
            + "      } else {\n"
            + "        status.textContent = 'Error: ' + data.error;\n"
            + "      }\n"
            + "    }\n"
            + "    async function copyEncoded() {\n"
            + "      const val = document.getElementById('encodedOut').value;\n"
            + "      if (!val) return;\n"
            + "      await navigator.clipboard.writeText(val);\n"
            + "      document.getElementById('encodeStatus').textContent = 'Copied to clipboard!';\n"
            + "    }\n"
            + "    async function decodeMsg() {\n"
            + "      const stego = document.getElementById('stegoIn').value;\n"
            + "      const status = document.getElementById('decodeStatus');\n"
            + "      const body = new URLSearchParams({ stego: stego });\n"
            + "      const res = await fetch('/api/decode', { method: 'POST', body: body });\n"
            + "      const data = await res.json();\n"
            + "      if (data.ok) {\n"
            + "        document.getElementById('decodedOut').value = data.decoded;\n"
            + "        status.textContent = 'Decoded successfully.';\n"
            + "      } else {\n"
            + "        document.getElementById('decodedOut').value = '';\n"
            + "        status.textContent = 'Error: ' + data.error;\n"
            + "      }\n"
            + "    }\n"
            + "  </script>\n"
            + "</body>\n"
            + "</html>\n";
}
