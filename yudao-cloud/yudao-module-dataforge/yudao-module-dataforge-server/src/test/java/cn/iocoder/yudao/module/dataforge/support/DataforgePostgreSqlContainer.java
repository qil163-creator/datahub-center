package cn.iocoder.yudao.module.dataforge.support;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/** Keeps Testcontainers lifecycle while allowing an explicitly isolated local PostgreSQL test server. */
public final class DataforgePostgreSqlContainer extends PostgreSQLContainer<DataforgePostgreSqlContainer> {
    private final String controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
    private final String testUser = System.getenv().getOrDefault("SHARING_TEST_JDBC_USER", "sharing_test");
    private String ownedDatabase;

    public DataforgePostgreSqlContainer() {
        super("postgres:16-alpine");
    }

    private boolean local() {
        return controlUrl != null && !controlUrl.isBlank();
    }

    @Override
    public synchronized void start() {
        if (!local()) {
            super.start();
            return;
        }
        if (ownedDatabase != null) return;
        if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
                || !Set.of("sharing_test", "dataforge_test").contains(testUser)) {
            throw new IllegalStateException("Only an explicit local isolated PostgreSQL test server and test role are accepted");
        }
        String name = "sharing_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(controlUrl, testUser, "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
            ownedDatabase = name;
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot create isolated test database", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (!local()) {
            super.stop();
            return;
        }
        if (ownedDatabase == null) return;
        if (!ownedDatabase.matches("sharing_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to drop an unowned database");
        }
        try (var connection = DriverManager.getConnection(controlUrl, testUser, "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + ownedDatabase);
            ownedDatabase = null;
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot clean up isolated test database", failure);
        }
    }

    @Override
    public String getJdbcUrl() {
        if (!local()) return super.getJdbcUrl();
        if (ownedDatabase == null) throw new IllegalStateException("Test database has not been started");
        return controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + ownedDatabase;
    }

    @Override
    public String getUsername() {
        return local() ? testUser : super.getUsername();
    }

    @Override
    public String getPassword() {
        return local() ? "" : super.getPassword();
    }

    @Override
    public boolean isRunning() {
        return local() ? ownedDatabase != null : super.isRunning();
    }
}
