public class EmojiStegoCodecTest {

    public static void main(String[] args) {
        EmojiStegoWebServer.EmojiStegoCodec codec = new EmojiStegoWebServer.EmojiStegoCodec();

        testRoundTrip(codec, "Hi Khalid, we will go tomorrow. 🚀", "✈️");
        testRoundTrip(codec, "Secret payload 123!", "👩‍💻");
        testRoundTrip(codec, "Unicode text: café • Tokyo • 🔐", "🛰️");
        testGraphemeClusters(codec);
        testEmptyAndMissingMarkers(codec);
        testChecksumFailure(codec);

        System.out.println("ALL STEGO-GLYPH TESTS PASSED");
    }

    private static void testRoundTrip(EmojiStegoWebServer.EmojiStegoCodec codec, String secret, String carrier) {
        String encoded = codec.encode(secret, carrier);
        if (!encoded.startsWith(carrier)) {
            throw new AssertionError("Encoded output should start with carrier emoji");
        }
        String decoded = codec.decode(encoded);
        if (!secret.equals(decoded)) {
            throw new AssertionError("Expected [" + secret + "] but got [" + decoded + "]");
        }
    }

    private static void testGraphemeClusters(EmojiStegoWebServer.EmojiStegoCodec codec) {
        if (!codec.splitGraphemeClusters("").isEmpty()) {
            throw new AssertionError("Empty string should produce 0 clusters");
        }
        int count = codec.splitGraphemeClusters("🚀🔥").size();
        if (count < 2) {
            throw new AssertionError("Expected at least 2 clusters, got " + count);
        }
    }

    private static void testEmptyAndMissingMarkers(EmojiStegoWebServer.EmojiStegoCodec codec) {
        assertThrowsIllegalArgument(() -> codec.encode("", "🚀"), "Empty secret should fail");
        assertThrowsIllegalArgument(() -> codec.encode("hi", ""), "Empty carrier should fail");
        assertThrowsIllegalArgument(() -> codec.decode(""), "Empty decode input should fail");
        assertThrowsIllegalArgument(() -> codec.decode("🚀"), "Plain carrier without markers should fail");
    }

    private static void testChecksumFailure(EmojiStegoWebServer.EmojiStegoCodec codec) {
        String encoded = codec.encode("Integrity check", "🛡️");
        StringBuilder tampered = new StringBuilder(encoded);

        int targetIdx = -1;
        for (int i = tampered.length() - 1; i >= 0; i--) {
            char ch = tampered.charAt(i);
            if (ch >= '\uFE00' && ch <= '\uFE0F') {
                targetIdx = i;
                break;
            }
        }

        if (targetIdx == -1) {
            throw new AssertionError("Expected variation selector characters in encoded output");
        }

        char orig = tampered.charAt(targetIdx);
        char replacement = (orig == '\uFE00') ? '\uFE01' : '\uFE00';
        tampered.setCharAt(targetIdx, replacement);

        assertThrowsIllegalArgument(
                () -> codec.decode(tampered.toString()),
                "Expected checksum or payload error on tampered input"
        );
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static void assertThrowsIllegalArgument(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected failure mode
        }
    }
}
