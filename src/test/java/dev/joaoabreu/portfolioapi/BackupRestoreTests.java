package dev.joaoabreu.portfolioapi;

import dev.joaoabreu.portfolioapi.recommendations.RecommendationImages;
import dev.joaoabreu.portfolioapi.recommendations.RecommendationModels;
import tools.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

class BackupRestoreTests {
    @TempDir Path temporary;

    @Test
    void customDumpAndSeparateImageBackupRestoreIntoAnIndependentDatabase() throws Exception {
        try (var source = database("backup_source"); var target = database("backup_restored")) {
            source.start(); target.start();
            var migration = flyway(source);
            assertEquals(9, migration.migrate().migrationsExecuted);

            Path liveImages = temporary.resolve("source-images");
            var images = new RecommendationImages(liveImages.toString());
            var png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
            String key = images.store(new ByteArrayInputStream(png.toByteArray())).key();
            byte[] originalImage = images.read(key);
            String revision = "12345678-1234-4234-8234-123456789abc";
            var json = new ObjectMapper();
            var details = new RecommendationModels.Details("synthetic-restore", RecommendationModels.Category.BOOKS,
                    key, "", 0, new RecommendationModels.Copy("Synthetic restore test", "Synthetic review only", "Synthetic image"),
                    new RecommendationModels.Copy("Teste sintético de restauração", "Somente revisão sintética", "Imagem sintética"), "");
            String payload = json.writeValueAsString(new RecommendationModels.Publication(1, revision, 0, details));

            try (Connection connection = connect(source); var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO contact_messages(name,email,message,submission_key) VALUES ('Synthetic restore test','synthetic@example.invalid','Synthetic message before backup','22345678-1234-4234-8234-123456789abc')");
                sql.executeUpdate("UPDATE visit_counter SET total=17 WHERE id=1");
                sql.executeUpdate("UPDATE profile_card_settings SET published='{\"synthetic\":\"before backup\"}', version=3 WHERE id=1");
                sql.executeUpdate("UPDATE publication_requests SET requested_generation=4, dispatched_generation=2");
                try (var draft = connection.prepareStatement("INSERT INTO recommendation_drafts(slug,payload) VALUES ('synthetic-restore',?) RETURNING id")) {
                    draft.setString(1, json.writeValueAsString(details));
                    try (var result = draft.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(1, result.getLong(1));
                        try (var selected = connection.prepareStatement("INSERT INTO recommendation_publications(recommendation_id,slug,revision,draft_version,image_key,payload) VALUES (?,'synthetic-restore',CAST(? AS uuid),0,?,?)")) {
                            selected.setLong(1, result.getLong(1)); selected.setString(2, revision);
                            selected.setString(3, key); selected.setString(4, payload); selected.executeUpdate();
                        }
                    }
                }
            }

            Path backup = Files.createDirectories(temporary.resolve("backup"));
            Path savedImages = Files.createDirectories(backup.resolve("images"));
            Files.copy(liveImages.resolve(key), savedImages.resolve(key));
            command(source, "pg_dump", "-U", source.getUsername(), "-d", source.getDatabaseName(),
                    "--format=custom", "--file=/tmp/synthetic-backup.dump");
            Path dump = backup.resolve("database.dump");
            source.copyFileFromContainer("/tmp/synthetic-backup.dump", dump.toString());
            String digest = sha256(Files.readAllBytes(dump));
            Files.writeString(backup.resolve("database.sha256"), digest);
            assertTrue(Files.size(dump) > 0);

            try (Connection connection = connect(source); var sql = connection.createStatement()) {
                sql.executeUpdate("UPDATE contact_messages SET message='Synthetic change after backup'");
                sql.executeUpdate("UPDATE visit_counter SET total=99 WHERE id=1");
                sql.executeUpdate("UPDATE profile_card_settings SET published=NULL, version=4 WHERE id=1");
            }
            Files.write(liveImages.resolve(key), new byte[] {0, 1, 2});
            assertFalse(images.exists(key));

            assertEquals(Files.readString(backup.resolve("database.sha256")), sha256(Files.readAllBytes(dump)));
            target.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/synthetic-restore.dump");
            command(target, "pg_restore", "-U", target.getUsername(), "-d", target.getDatabaseName(),
                    "--no-owner", "--exit-on-error", "/tmp/synthetic-restore.dump");
            var restoredMigration = flyway(target);
            restoredMigration.validate();
            assertEquals(0, restoredMigration.migrate().migrationsExecuted);

            Path restoredImages = Files.createDirectories(temporary.resolve("restored-images"));
            Files.copy(savedImages.resolve(key), restoredImages.resolve(key));
            var restored = new RecommendationImages(restoredImages.toString());
            assertArrayEquals(originalImage, restored.read(key));
            try (var input = new ByteArrayInputStream(restored.read(key))) {
                BufferedImage image = ImageIO.read(input);
                assertEquals(2, image.getWidth()); assertEquals(3, image.getHeight());
            }

            try (Connection connection = connect(target); var sql = connection.createStatement()) {
                try (var result = sql.executeQuery("SELECT name,email,message,status,attempts FROM contact_messages")) {
                    assertTrue(result.next());
                    assertEquals("Synthetic restore test", result.getString("name"));
                    assertEquals("synthetic@example.invalid", result.getString("email"));
                    assertEquals("Synthetic message before backup", result.getString("message"));
                    assertEquals("pending", result.getString("status")); assertEquals(0, result.getInt("attempts"));
                    assertFalse(result.next());
                }
                try (var result = sql.executeQuery("SELECT total FROM visit_counter WHERE id=1")) {
                    assertTrue(result.next()); assertEquals(17, result.getLong(1));
                }
                try (var result = sql.executeQuery("SELECT published, version FROM profile_card_settings WHERE id=1")) {
                    assertTrue(result.next()); assertEquals("{\"synthetic\":\"before backup\"}", result.getString(1));
                    assertEquals(3, result.getLong(2));
                }
                try (var result = sql.executeQuery("SELECT requested_generation,dispatched_generation FROM publication_requests WHERE id=1")) {
                    assertTrue(result.next()); assertEquals(4, result.getLong(1)); assertEquals(2, result.getLong(2));
                }
                try (var result = sql.executeQuery("SELECT p.image_key,p.payload,p.revision,d.slug FROM recommendation_publications p JOIN recommendation_drafts d ON d.id=p.recommendation_id")) {
                    assertTrue(result.next()); assertEquals(key, result.getString(1));
                    assertEquals(payload, result.getString(2)); assertEquals(revision, result.getString(3));
                    var restoredPublication = json.readValue(result.getString(2), RecommendationModels.Publication.class);
                    assertEquals(details, restoredPublication.data());
                    assertEquals(1, restoredPublication.recommendationId());
                    assertEquals("synthetic-restore", result.getString(4)); assertTrue(restored.exists(result.getString(1)));
                }
                try (var result = sql.executeQuery("INSERT INTO contact_messages(name,email,message) VALUES ('Synthetic next identity','next@example.invalid','Synthetic message after restoration') RETURNING id")) {
                    assertTrue(result.next()); assertEquals(2, result.getLong(1));
                }
                assertThrows(java.sql.SQLException.class, () -> sql.executeUpdate("INSERT INTO recommendation_publications(recommendation_id,slug,revision,draft_version,image_key,payload) VALUES (999,'invalid-reference','32345678-1234-4234-8234-123456789abc',0,'','{}')"));
            }
        }
    }

    PostgreSQLContainer database(String name) {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName(name).withUsername("restore_test").withPassword("synthetic-test-only");
    }
    Flyway flyway(PostgreSQLContainer container) {
        return Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load();
    }
    Connection connect(PostgreSQLContainer container) throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
    void command(PostgreSQLContainer container, String... args) throws Exception {
        var result = container.execInContainer(args);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }
    String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
