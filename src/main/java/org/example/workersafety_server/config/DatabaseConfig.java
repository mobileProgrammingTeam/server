package org.example.workersafety_server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
public class DatabaseConfig {
    @Bean
    DataSource dataSource(@Value("${app.db-path}") String dbPath) throws Exception {
        Path path = Path.of(dbPath).toAbsolutePath();
        Files.createDirectories(path.getParent());
        if (!Files.isWritable(path.getParent())) throw new IllegalStateException("SQLite directory is not writable: " + path.getParent());
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(5000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA journal_mode")) {
            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) throw new IllegalStateException("SQLite WAL mode unavailable");
        } catch (Exception e) {
            dataSource.close();
            throw e;
        }
        return dataSource;
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
