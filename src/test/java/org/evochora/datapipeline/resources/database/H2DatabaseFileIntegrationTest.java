package org.evochora.datapipeline.resources.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests of {@link H2Database} that need the database in a file: where a configured URL puts it,
 * and what survives {@link H2Database#close()}.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseFileIntegrationTest {

    @Test
    void testJdbcUrl_DirectJdbcUrlProvided(@TempDir Path dir) {
        // A directly provided URL is used as it stands, so the database file appears where it points.
        Path dbPath = dir.resolve("h2-direct");
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:" + dbPath.toString().replace("\\", "/") + ";MODE=PostgreSQL\"");

        H2Database db = new H2Database("test", config);
        db.close();

        assertTrue(Files.exists(Path.of(dbPath + ".mv.db")),
            "The provided URL should be used as it stands: " + dbPath);
    }

    @Test
    void testJdbcUrl_WithVariableExpansion(@TempDir Path dir) {
        // When jdbcUrl contains variables, they should be expanded via HOCON's resolve()
        // Use ConfigFactory.parseMap for the variable definition to avoid caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of("test.db.dir", dir.toString().replace("\\", "/")));
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:\"${test.db.dir}\"/h2-test;MODE=PostgreSQL\"")
            .withFallback(varsConfig)
            .resolve();
        
        H2Database db = new H2Database("test", config);
        
        assertNotNull(db);
        db.close();
    }

    @Test
    void closeKeepsWhatWasCommittedJustBefore(@TempDir Path dir) throws Exception {
        // The options of the shipped configuration, under which the database stays open until close()
        String url = "jdbc:h2:" + dir.resolve("committed").toString().replace("\\", "/")
            + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1";
        H2Database db = new H2Database("committed", ConfigFactory.parseMap(Map.of("jdbcUrl", url)));
        try (Connection connection = (Connection) db.acquireDedicatedConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE written (x INT)");
            statement.execute("INSERT INTO written VALUES (1)");
            connection.commit();
        }

        db.close();

        try (Connection reopened = DriverManager.getConnection(url, "sa", "");
             java.sql.Statement statement = reopened.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM written")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "the row committed before close() is in the file");
            statement.execute("SHUTDOWN");
        }
    }
}
