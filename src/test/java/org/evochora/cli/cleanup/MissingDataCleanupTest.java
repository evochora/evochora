package org.evochora.cli.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * A cleanup that is pointed at data that does not exist finds no runs and leaves the filesystem
 * as it found it: neither an empty database nor a broker journal appears. A database that exists
 * is read as before.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class MissingDataCleanupTest {

    @TempDir
    Path tempDir;

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*DatabaseCleaner.*",
               messagePattern = ".*Database does not exist.*")
    void databaseCleaner_createsNoDatabase() {
        Path databaseDir = tempDir.resolve("database");
        Config config = ConfigFactory.parseMap(Map.of(
            "pipeline.database.jdbcUrl", "jdbc:h2:" + databaseDir.resolve("indexdb").toAbsolutePath(),
            "pipeline.database.username", "sa",
            "pipeline.database.password", ""));
        DatabaseCleaner cleaner = new DatabaseCleaner(config);
        StringWriter output = new StringWriter();

        assertThat(cleaner.listRunIds()).isEmpty();
        CleanupService.AreaStats stats = cleaner.cleanup(List.of(), List.of(), false, new PrintWriter(output));
        CompactionResult compaction = cleaner.compact();

        assertThat(stats.kept()).isZero();
        assertThat(stats.deleted()).isZero();
        assertThat(output.toString()).doesNotContain("ERROR");
        assertThat(compaction.success()).isFalse();
        assertThat(compaction.message()).contains("does not exist");
        assertThat(Files.exists(databaseDir)).as("no database may be created").isFalse();
    }

    @Test
    void databaseCleaner_findsTheRunsOfAnExistingDatabase() throws Exception {
        String jdbcUrl = "jdbc:h2:" + tempDir.resolve("database").resolve("indexdb").toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"SIM_20260117_22042059_D59177FC_1EBA_4CE8_85A6_36BD2032E3CB\"");
        }
        Config config = ConfigFactory.parseMap(Map.of(
            "pipeline.database.jdbcUrl", jdbcUrl,
            "pipeline.database.username", "sa",
            "pipeline.database.password", ""));

        assertThat(new DatabaseCleaner(config).listRunIds())
            .containsExactly("20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb");
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*TopicCleaner.*",
               messagePattern = ".*Topic directory does not exist.*")
    void topicCleaner_startsNoBroker() {
        Path topicDir = tempDir.resolve("topic");
        Config config = ConfigFactory.parseMap(Map.of(
            "node.processes.topic-broker.options.dataDirectory", topicDir.toAbsolutePath().toString()));
        TopicCleaner cleaner = new TopicCleaner(config);

        assertThat(cleaner.listRunIds()).isEmpty();
        CleanupService.AreaStats stats = cleaner.cleanup(List.of(), List.of(), false,
            new PrintWriter(new StringWriter()));

        assertThat(stats.kept()).isZero();
        assertThat(stats.deleted()).isZero();
        assertThat(Files.exists(topicDir)).as("no broker journal may be created").isFalse();
    }
}
