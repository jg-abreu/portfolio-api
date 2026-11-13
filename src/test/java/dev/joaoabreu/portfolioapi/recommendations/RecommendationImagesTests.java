package dev.joaoabreu.portfolioapi.recommendations;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationImagesTests {
    @TempDir Path directory;

    @Test
    void importsJpegAsPngWithStableHashAndDeduplicates() throws Exception {
        var images = new RecommendationImages(directory.toString());
        byte[] jpeg = image(24, 32, "jpeg");
        var stored = images.store(new ByteArrayInputStream(jpeg));
        assertEquals(24, stored.width());
        assertEquals(32, stored.height());
        byte[] png = images.read(stored.key());
        assertEquals(stored.key(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)) + ".png");
        assertEquals(137, Byte.toUnsignedInt(png[0]));
        assertEquals(stored, images.store(new ByteArrayInputStream(jpeg)));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test
    void rejectsInvalidFormatsOversizedUploadsAndExcessiveDimensions() throws Exception {
        var images = new RecommendationImages(directory.toString());
        for (byte[] bytes : new byte[][] { new byte[0], "<svg onload='unsafe()'/>".getBytes(),
                new byte[RecommendationImages.MAX_UPLOAD + 1], image(4097, 1, "png"), image(4000, 4000, "png") }) {
            var error = assertThrows(ResponseStatusException.class, () -> images.store(new ByteArrayInputStream(bytes)));
            assertEquals(400, error.getStatusCode().value());
        }
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }

    @Test
    void rejectsPathsAndMissingFiles() {
        var images = new RecommendationImages(directory.toString());
        for (String key : new String[] { "../../private.png", "https://example.com/image.png", "a".repeat(64) + ".png" }) {
            var error = assertThrows(ResponseStatusException.class, () -> images.read(key));
            assertEquals(404, error.getStatusCode().value());
            assertFalse(images.exists(key));
        }
    }

    @Test
    void corruptedStoredImagesAreNeverServedOrReused() throws Exception {
        var images = new RecommendationImages(directory.toString());
        byte[] png = image(2, 3, "png");
        var stored = images.store(new ByteArrayInputStream(png));
        Files.write(directory.resolve(stored.key()), "corrupted".getBytes());
        assertFalse(images.exists(stored.key()));
        assertThrows(ResponseStatusException.class, () -> images.read(stored.key()));
        assertThrows(ResponseStatusException.class, () -> images.store(new ByteArrayInputStream(png)));
    }

    private byte[] image(int width, int height, String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes));
        return bytes.toByteArray();
    }
}
