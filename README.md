# Emoji Secret Messenger

A Java 8+ steganography tool that hides secret UTF-8 messages inside carrier emojis using Unicode variation selectors (`U+FE00`–`U+FE0F`), wrapped in framing markers (`U+2062` / `U+2063`) with a 1-byte XOR checksum.

Includes three entry points:
* **Desktop GUI (`EmojiStegoApp`)**: Swing interface with live UTF-8 byte/code-point counts, clipboard copy/paste, clear actions, and `.txt` load/save.
* **Web Server (`EmojiStegoWebServer`)**: Built-in Java `HttpServer` serving a browser UI and `/api/encode` + `/api/decode` endpoints (listens on `PORT` or `8080`).
* **Console CLI (`EmojiStegoWebServer --cli`)**: Interactive terminal mode for environments without a browser or GUI.

## Compile and Run

Compile all sources with UTF-8 encoding:

```bash
javac -encoding UTF-8 EmojiStegoWebServer.java EmojiStegoApp.java EmojiStegoCodecTest.java
