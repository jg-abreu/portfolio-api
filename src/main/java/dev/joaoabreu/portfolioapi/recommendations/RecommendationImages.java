package dev.joaoabreu.portfolioapi.recommendations;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationImages {
    static final int MAX_UPLOAD = 2 * 1024 * 1024;
    static final int MAX_STORED = 8 * 1024 * 1024;
    private final Path directory;

    public RecommendationImages(@Value("${portfolio.recommendations.media-dir:./data/recommendations}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public synchronized StoredImage store(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_UPLOAD + 1);
            if (bytes.length == 0 || bytes.length > MAX_UPLOAD) throw invalid("Image must be between 1 byte and 2 MiB");
            BufferedImage decoded;
            try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalid("Only PNG and JPEG images are supported");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid("Only PNG and JPEG images are supported");
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 12_000_000)
                        throw invalid("Image dimensions exceed the limit");
                    decoded = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
            if (decoded == null) throw invalid("Image cannot be decoded");
            var normalized = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = normalized.createGraphics();
            try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(normalized, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            byte[] png = output.toByteArray();
            if (png.length > MAX_STORED) throw invalid("Normalized image exceeds 8 MiB");
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)) + ".png";
            ensureDirectory();
            Path destination = resolve(key);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!MessageDigest.isEqual(read(key), png)) throw new IOException("Invalid image destination");
            } else {
                try (var entries = Files.list(directory)) {
                    if (entries.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.png")).limit(500).count() >= 500)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Image storage limit reached");
                }
                Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
                try {
                    Files.write(temporary, png);
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return new StoredImage(key, normalized.getWidth(), normalized.getHeight());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to process image", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public byte[] read(String key) {
        Path path = resolve(key);
        try {
            for (Path parent = directory; parent != null; parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) throw missing();
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_STORED + 1);
                if (bytes.length > MAX_STORED || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(key.substring(0, 64))) throw missing();
                return bytes;
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (IOException exception) {
            throw missing();
        }
    }

    public boolean exists(String key) {
        try { read(key); return true; } catch (ResponseStatusException exception) { return false; }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}\\.png")) throw missing();
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory) || !directory.equals(path.getParent())) throw missing();
        return path;
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        for (Path path = directory; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic image directories are not supported");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
    }

    public record StoredImage(String key, int width, int height) {}
}
