package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 产品 URL 参数格式的确定性输出；不表示已完成数据库产品实机认证。 */
class JdbcUrlParamsTest {
    @Test void requiredMysqlQueryOptionsReplaceEveryCaseInsensitiveDuplicate() {
        String url = "jdbc:mysql://host:9030/db?connectTimeout=3&useServerPrepStmts=false"
                + "&USESERVERPREPSTMTS=false&%75seServerPrepStmts=false&emulateUnsupportedPstmts=true"
                + "&sessionVariables=sql_mode%3D%27NO_BACKSLASH_ESCAPES%27";
        assertEquals("jdbc:mysql://host:9030/db?connectTimeout=3"
                        + "&sessionVariables=sql_mode%3D%27NO_BACKSLASH_ESCAPES%27"
                        + "&emulateUnsupportedPstmts=false&useServerPrepStmts=true",
                JdbcUrlUtils.overrideMysqlQueryParams(url,
                        Map.of("useServerPrepStmts", true, "emulateUnsupportedPstmts", false)));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.overrideMysqlQueryParams(
                "jdbc:mysql://host/db", Map.of("useServerPrepStmts", true, "USESERVERPREPSTMTS", false)));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.overrideMysqlQueryParams(
                "jdbc:mysql://host/db", Map.of("password", "synthetic")));
    }

    @Test void requiredMysqlQueryOptionsRejectMalformedNamesWithoutReturningTheUrl() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.overrideMysqlQueryParams(
                "jdbc:mysql://host/db?%ZZ=value", Map.of("useServerPrepStmts", true)));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.overrideMysqlQueryParams(
                "jdbc:mysql://host/db#ignored", Map.of("useServerPrepStmts", true)));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.overrideMysqlQueryParams(
                "jdbc:postgresql://host/db", Map.of("useServerPrepStmts", true)));
    }

    @Test void phoenixKeepsThinUrlValuesLiteralAndUsesSemicolons() {
        assertEquals("jdbc:phoenix:thin:url=http://host:8765;serialization=PROTOBUF;authentication=BASIC;http_connection_timeout=3000",
                JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX,
                        "jdbc:phoenix:thin:url=http://host:8765;serialization=PROTOBUF",
                        Map.of("authentication", "BASIC", "http_connection_timeout", 3000)));
        assertEquals("jdbc:phoenix:thin:url=http://host:8765?key=a=b&mode=1",
                JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, "jdbc:phoenix:thin:",
                        Map.of("url", "http://host:8765?key=a=b&mode=1")));
    }
    @Test void hanaUsesQueryParametersAfterDatabaseName() {
        assertEquals("jdbc:sap://host:30015/?databaseName=DB&encrypt=true&validateCertificate=true",
                JdbcUrlUtils.appendParams(ProductTypeEnum.SAPHANA, "jdbc:sap://host:30015/?databaseName=DB",
                        Map.of("encrypt", true, "validateCertificate", true)));
    }
    @Test void postgresQueryParametersAreEncodedAndStable() {
        assertEquals("jdbc:postgresql://host:5432/db?connectTimeout=3&applicationName=Data%20Hub%26test&sslmode=verify-full",
                JdbcUrlUtils.appendParams(ProductTypeEnum.GAUSSDB, "jdbc:postgresql://host:5432/db?connectTimeout=3",
                        Map.of("applicationName", "Data Hub&test", "sslmode", "verify-full")));
    }
    @Test void prestoUsesQueryParametersAndDoesNotRewriteCatalogSchemaPath() {
        assertEquals("jdbc:presto://host:8080/hive/default?SSL=true",
                JdbcUrlUtils.appendParams(ProductTypeEnum.PRESTO, "jdbc:presto://host:8080/hive/default", Map.of("SSL", true)));
    }
    @Test void sqlServerUsesItsOwnBraceEscape() {
        assertEquals("jdbc:sqlserver://host:1433;databaseName=db;applicationName={demo;x=2}}}",
                JdbcUrlUtils.appendParams(ProductTypeEnum.SQLSERVER, "jdbc:sqlserver://host:1433;databaseName=db",
                        Map.of("applicationName", "demo;x=2}")));
    }
    @Test void db2UsesColonThenSemicolonProperties() {
        assertEquals("jdbc:db2://host:50000/db:currentSchema=SCHEMA;sslConnection=true;",
                JdbcUrlUtils.appendParams(ProductTypeEnum.DB2, "jdbc:db2://host:50000/db",
                        Map.of("currentSchema", "SCHEMA", "sslConnection", true)));
        assertEquals("jdbc:db2://host:50000/db:currentSchema=SCHEMA;sslConnection=true;",
                JdbcUrlUtils.appendParams(ProductTypeEnum.DB2, "jdbc:db2://host:50000/db:currentSchema=SCHEMA;",
                        Map.of("sslConnection", true)));
    }
    @Test void hiveConnectionPropertiesUseSemicolons() {
        assertEquals("jdbc:hive2://host:10000/default;auth=LDAP;ssl=true",
                JdbcUrlUtils.appendParams(ProductTypeEnum.HIVE, "jdbc:hive2://host:10000/default", Map.of("auth", "LDAP", "ssl", true)));
    }
    @Test void rawPhoenixQueryOrSemicolonPropertiesUseOneSharedParser() {
        String url = "jdbc:phoenix:thin:url=http://host:8765;serialization=PROTOBUF";
        assertEquals(url + ";authentication=BASIC;http_connection_timeout=3000",
                JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, url, "authentication=BASIC&http_connection_timeout=3000"));
        assertEquals(url + ";authentication=NONE;http_connection_timeout=3000",
                JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, url, ";authentication=NONE;http_connection_timeout=3000"));
        assertEquals("jdbc:postgresql://host/db?applicationName=Data%20Hub%26Team",
                JdbcUrlUtils.appendParams(ProductTypeEnum.GAUSSDB, "jdbc:postgresql://host/db", "?applicationName=Data+Hub%26Team"));
    }
    @Test void rawParamsRejectEncodedCredentialKeysAndDuplicates() {
        String url = "jdbc:phoenix:thin:url=http://host:8765";
        for (String input : java.util.List.of("password=secret", "user=account", "avatica_user=account", "avatica_password=secret",
                "token=secret", "accessKeySecret=secret", "pass%77ord=secret", "http_password=secret")) {
            assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, url, input));
        }
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, url, "authentication=BASIC&Authentication=NONE"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX, url, "not-a-property"));
    }
    @Test void rejectsPropertyInjectionAndStructuredValues() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.PHOENIX,
                "jdbc:phoenix:thin:url=http://host:8765", Map.of("authentication", "BASIC;url=http://other")));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.POSTGRESQL,
                "jdbc:postgresql://host/db", Map.of("invalid&key", "value")));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.POSTGRESQL,
                "jdbc:postgresql://host/db", Map.of("options", Map.of("a", "b"))));
        assertThrows(UnsupportedOperationException.class, () -> JdbcUrlUtils.appendParams(ProductTypeEnum.ODPS,
                "jdbc:placeholder", Map.of("ignored", "x")));
    }
}
