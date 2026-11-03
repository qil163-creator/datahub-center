package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionSettingsTest {
    private static DatabaseTypeDO type(String code) { return DatabaseTypeDO.builder().typeCode(code).build(); }
    private static DatabaseDO connection(String json) {
        return DatabaseDO.builder().databaseIp("service.example.invalid").databasePort("22")
                .userName("fixture-user").databaseName("fixture").extendConfig(json).build();
    }

    @Test void endpointProtocolsNeedNoInventedJdbcOrHostColumns() {
        for (String code : List.of("OSS", "MINIO", "ODPS", "MILVUS", "INFLUXDB2")) {
            var db = connection("{\"schemaVersion\":1,\"endpoint\":\"https://service.example.invalid\"}");
            db.setDatabaseIp(null); db.setDatabasePort(null);
            assertEquals("https://service.example.invalid", ConnectionSettings.read(db, type(code)).get("endpoint", ""));
        }
    }

    @Test void rejectsSecretsUnknownFieldsAndUnversionedConfiguration() {
        for (String json : List.of("{}", "{\"schemaVersion\":2}", "{\"schemaVersion\":1.5}", "{\"schemaVersion\":\"1\"}", "[]",
                "{\"schemaVersion\":1,\"password\":\"fixture\"}",
                "{\"schemaVersion\":1,\"sasl.jaas.config\":\"fixture\"}",
                "{\"schemaVersion\":1,\"tls\":\"true\"}",
                "{\"schemaVersion\":1,\"servers\":[1]}")) {
            assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(connection(json), type("REDIS")));
        }
    }

    @Test void endpointsCannotCarryCredentialsOrAnUnsupportedScheme() {
        for (String endpoint : List.of("https://user:fixture@service.example.invalid", "file:///tmp/fixture",
                "https://service.example.invalid?token=fixture", "https://service.example.invalid#fixture")) {
            String json = "{\"schemaVersion\":1,\"endpoint\":\"" + endpoint + "\"}";
            assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(connection(json), type("MINIO")));
        }
    }

    @Test void kafkaServersRoundTripWithoutJaasOrArbitraryProperties() {
        var settings = ConnectionSettings.read(connection("""
                {"schemaVersion":1,"servers":["broker.example.invalid:9092","other.example.invalid:9092"],
                 "securityProtocol":"SASL_SSL","saslMechanism":"SCRAM-SHA-512","tls":true}
                """), type("KAFKA"));
        assertEquals("broker.example.invalid:9092,other.example.invalid:9092", settings.get("servers", ""));
        assertTrue(settings.flag("tls", false));
        assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(
                connection("{\"schemaVersion\":1,\"servers\":[\"broker.example.invalid:9092/path\"]}"), type("KAFKA")));
    }

    @Test void sftpRequiresAnExplicitTrustedHostKey() {
        var db = connection("{\"schemaVersion\":1,\"basePath\":\"/\",\"hostKeyFingerprint\":\"SHA256:" + "A".repeat(43) + "\"}");
        assertEquals("SFTP", ConnectionSettings.read(db, type("sftp")).code());
        db.setExtendConfig("{\"schemaVersion\":1,\"basePath\":\"/\"}");
        assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(db, type("SFTP")));
    }

    @Test void newJdbcProductsKeepExistingUrlParameterContract() {
        var db = connection("sslmode=verify-full"); db.setJdbcUrl("jdbc:postgresql://service.example.invalid/fixture");
        assertTrue(ConnectionSettings.read(db, type("GAUSSDB")).properties().isEmpty());
        db.setExtendConfig("password=fixture-secret");
        assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(db, type("GAUSSDB")));
        assertFalse(ConnectionSettings.managed(type("POSTGRESQL")));
    }

    @Test void explicitAuthenticationCannotFallBackToAnonymous() {
        for (String mode : List.of("PASSWORD", "TOKEN")) {
            var db = connection("{\"schemaVersion\":1,\"endpoint\":\"https://service.example.invalid\",\"authenticationMode\":\"" + mode + "\"}");
            var settings = ConnectionSettings.read(db, type("MILVUS"));
            assertThrows(IllegalArgumentException.class, () -> settings.validateCredentials(db, ""));
            settings.validateCredentials(db, "fixture-secret");
        }
        var db = connection("{\"schemaVersion\":1,\"servers\":[\"broker.example.invalid:9876\"],\"authenticationMode\":\"ACL\"}");
        var settings = ConnectionSettings.read(db, type("ROCKETMQ"));
        assertThrows(IllegalArgumentException.class, () -> settings.validateCredentials(db, ""));
        db.setUserName("");
        assertThrows(IllegalArgumentException.class, () -> settings.validateCredentials(db, "fixture-secret"));
        db.setExtendConfig("{\"schemaVersion\":1,\"servers\":[\"broker.example.invalid:9876\"]}");
        assertTrue(ConnectionSettings.passwordless(db, type("ROCKETMQ")));
        ConnectionSettings.read(db, type("ROCKETMQ")).validateCredentials(db, null);
    }

    @Test void timeoutUsesAnOptionalBoundedIntegerAndRejectsOverflow() {
        assertEquals(10, ConnectionSettings.read(connection("{\"schemaVersion\":1}"), type("RABBITMQ")).timeout().toSeconds());
        for (int timeout : List.of(1, 10, 30)) {
            var db = connection("{\"schemaVersion\":1,\"timeoutSeconds\":" + timeout + "}");
            assertEquals(timeout, ConnectionSettings.read(db, type("RABBITMQ")).timeout().toSeconds());
        }
        for (String timeout : List.of("0", "31", "1.5", "\"10\"", "null", "true", "4294967297")) {
            var db = connection("{\"schemaVersion\":1,\"timeoutSeconds\":" + timeout + "}");
            assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(db, type("RABBITMQ")));
        }
        assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(
                connection("{\"schemaVersion\":4294967297}"), type("RABBITMQ")));
    }

    @Test void neo4jValidatesBoltAddressSeparatelyFromDatabaseAndCredentials() {
        for (String endpoint : List.of("bolt://service.example.invalid", "neo4j+s://service.example.invalid:7687/", "bolt://[::1]:7687")) {
            var db = connection("{\"schemaVersion\":1,\"endpoint\":\"" + endpoint + "\"}");
            var settings = ConnectionSettings.read(db, type("NEO4J"));
            assertThrows(IllegalArgumentException.class, () -> settings.validateCredentials(db, ""));
            settings.validateCredentials(db, "fixture-password");
        }
        for (String endpoint : List.of("bolt://service.example.invalid:", "bolt://service.example.invalid:0", "bolt://service.example.invalid:65536",
                "bolt://service.example.invalid/neo4j", "bolt+ssc://service.example.invalid", "http://service.example.invalid:7474")) {
            var db = connection("{\"schemaVersion\":1,\"endpoint\":\"" + endpoint + "\"}");
            assertThrows(IllegalArgumentException.class, () -> ConnectionSettings.read(db, type("NEO4J")));
        }
    }

    @Test void equivalentDefaultsAndTimeoutDoNotChangeCredentialTarget() {
        var original = connection("{\"schemaVersion\":1,\"endpoint\":\"https://service.example.invalid\"}");
        var equivalent = connection("{\"endpoint\":\"https://service.example.invalid\",\"schemaVersion\":1,\"pathStyle\":true,\"region\":\"\",\"timeoutSeconds\":30}");
        assertEquals(ConnectionSettings.read(original, type("MINIO")).credentialProperties(),
                ConnectionSettings.read(equivalent, type("MINIO")).credentialProperties());
        equivalent.setExtendConfig(equivalent.getExtendConfig().replace("true", "false"));
        assertNotEquals(ConnectionSettings.read(original, type("MINIO")).credentialProperties(),
                ConnectionSettings.read(equivalent, type("MINIO")).credentialProperties());
    }
}
