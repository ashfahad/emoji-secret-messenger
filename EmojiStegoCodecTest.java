import java.util.ArrayList;
import java.util.List;

public class EmojiStegoCodecTest {

    private final EmojiStegoWebServer.EmojiStegoCodec codec =
            new EmojiStegoWebServer.EmojiStegoCodec();

    void encodesAndDecodesNormalText() {
        String secret = "Hi Khalid, we will go tomorrow.";
        String carrier = "✈️😛";

        String encoded = codec.encode(secret, carrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded, "Decoded message should match original");
        assertTrue(encoded.startsWith("✈️"), "Carrier start should remain visible");
    }

    void encodesAndDecodesEmojisInsideSecretMessage() {
        String secret = "Secret launch 🚀🔥 at 10 PM — don't miss it! 🎯";
        String carrier = "😎";

        String encoded = codec.encode(secret, carrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded, "Decoded message with emojis should match original");
    }

    void handlesZwjAndCompoundCarrierEmojis() {
        String secret = "Lab demo ready at 2 PM.";
        String zwjCarrier = "👩‍💻";

        String encoded = codec.encode(secret, zwjCarrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded, "Decoded message with ZWJ carrier should match original");
        assertTrue(encoded.codePointCount(0, encoded.length()) > 3, "Expected hidden code points appended");
    }

    void rejectsPlainEmojiWithoutMarkers() {
        assertThrowsIllegalArgument(() -> codec.decode("✈️😛"), "Plain emoji should not decode");
        assertThrowsIllegalArgument(() -> codec.decode(""), "Empty string should not decode");
        assertThrowsIllegalArgument(() -> codec.decode(null), "Null input should not decode");
    }

    void rejectsEmptySecretOrMissingCarrier() {
        assertThrowsIllegalArgument(() -> codec.encode("", "😛"), "Empty secret should fail");
        assertThrowsIllegalArgument(() -> codec.encode("Hello", ""), "Empty carrier should fail");
        assertThrowsIllegalArgument(() -> codec.encode("Hello", "   "), "Blank carrier should fail");
    }

void detectsCorruptedNibbleViaChecksum() {
        String encoded = codec.encode("Hi Khalid, we will go tomorrow.", "✈️😛");

        List<Integer> codePoints = new ArrayList<Integer>();
        boolean seenStart = false;
        boolean flipped = false;
        for (int i = 0; i < encoded.length(); ) {
            int cp = encoded.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x2062) {
                seenStart = true;
            } else if (seenStart && !flipped && cp >= 0xFE00 && cp <= 0xFE0F) {
                cp = (cp == 0xFE00) ? 0xFE01 : 0xFE00;
                flipped = true;
            }
            codePoints.add(cp);
        }

        assertTrue(flipped, "Expected at least one variation selector nibble in payload");

        StringBuilder tampered = new StringBuilder();
        for (int cp : codePoints) {
            tampered.appendCodePoint(cp);
        }

        assertThrowsIllegalArgument(() -> codec.decode(tampered.toString()), "Tampered nibble should fail checksum");
    }
    
    void detectsTruncatedPayloadMissingEndMarker() {
        String encoded = codec.encode("Secret message", "😛");
        int endMarkerCp = 0x2063;
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < encoded.length(); ) {
            int cp = encoded.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != endMarkerCp) {
                truncated.appendCodePoint(cp);
            }
        }

        assertThrowsIllegalArgument(() -> codec.decode(truncated.toString()), "Missing end marker should fail");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " | expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertThrowsIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + " | expected IllegalArgumentException");
    }

    public static void main(String[] args) {
        EmojiStegoCodecTest runner = new EmojiStegoCodecTest();
        runner.encodesAndDecodesNormalText();
        runner.encodesAndDecodesEmojisInsideSecretMessage();
        runner.handlesZwjAndCompoundCarrierEmojis();
        runner.rejectsPlainEmojiWithoutMarkers();
        runner.rejectsEmptySecretOrMissingCarrier();
        runner.detectsCorruptedNibbleViaChecksum();
        runner.detectsTruncatedPayloadMissingEndMarker();
        System.out.println("All EmojiStegoCodec tests passed!");
    }
}
