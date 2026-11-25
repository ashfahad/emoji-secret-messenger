import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiStegoCodecTest {

    private final EmojiStegoWebServer.EmojiStegoCodec codec =
            new EmojiStegoWebServer.EmojiStegoCodec();

    @Test
    void encodesAndDecodesNormalText() {
        String secret = "Hi Khalid, we will go tomorrow.";
        String carrier = "✈️😛";

        String encoded = codec.encode(secret, carrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded);
        assertTrue(encoded.startsWith("✈️"), "Carrier start should remain visible");
    }

    @Test
    void encodesAndDecodesEmojisInsideSecretMessage() {
        String secret = "Secret launch 🚀🔥 at 10 PM — don't miss it! 🎯";
        String carrier = "😎";

        String encoded = codec.encode(secret, carrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded);
    }

    @Test
    void handlesZwjAndCompoundCarrierEmojis() {
        String secret = "Lab demo ready at 2 PM.";
        String zwjCarrier = "👩‍💻";

        String encoded = codec.encode(secret, zwjCarrier);
        String decoded = codec.decode(encoded);

        assertEquals(secret, decoded);
        assertTrue(encoded.codePointCount(0, encoded.length()) > 3);
    }

    @Test
    void rejectsPlainEmojiWithoutMarkers() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("✈️😛"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
    }

    @Test
    void rejectsEmptySecretOrMissingCarrier() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode("", "😛"));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("Hello", ""));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("Hello", "   "));
    }

    @Test
    void detectsCorruptedNibbleViaChecksum() {
        String encoded = codec.encode("Hi Khalid, we will go tomorrow.", "✈️😛");

        List<Integer> codePoints = new ArrayList<Integer>();
        boolean flipped = false;
        for (int i = 0; i < encoded.length(); ) {
            int cp = encoded.codePointAt(i);
            i += Character.charCount(cp);
            if (!flipped && cp >= 0xFE00 && cp <= 0xFE0F) {
                cp = (cp == 0xFE00) ? 0xFE01 : 0xFE00;
                flipped = true;
            }
            codePoints.add(cp);
        }

        assertTrue(flipped, "Expected at least one variation selector nibble in encoded output");

        StringBuilder tampered = new StringBuilder();
        for (int cp : codePoints) {
            tampered.appendCodePoint(cp);
        }

        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered.toString()));
    }

    @Test
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

        assertThrows(IllegalArgumentException.class, () -> codec.decode(truncated.toString()));
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
