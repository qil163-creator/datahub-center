package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEndpointTest {

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"UNKNOWN", "SQLITE3", "ODPS", "NEO4J", "MILVUS", "INFLUXDB2"})
    void everyNetworkJdbcProductHasAnExplicitEndpointParser(ProductTypeEnum product) {
        String url = product.getUrl().replace("{host}", "localhost").replace("{port}", "12345")
                .replace("{database}", "diagnostic");
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        var endpoint = DiagnosticEndpoint.parse(product, url);
        assertTrue(endpoint.isPresent(), product + ": " + url);
        assertEquals(12345, endpoint.orElseThrow().port());
        assertEquals("localhost", endpoint.orElseThrow().host());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "DORIS", "STARROCKS", "TIDB"})
    void mysqlProtocolProductsResolveTheirActualJdbcTarget(ProductTypeEnum product) {
        var endpoint = DiagnosticEndpoint.parse(product,
                "jdbc:mysql://localhost:9030/demo?useSSL=false&rewriteBatchedStatements=true").orElseThrow();
        assertEquals("localhost", endpoint.host());
        assertEquals(9030, endpoint.port());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "GREENPLUM", "GAUSSDB"})
    void postgresProtocolProductsResolveTheirActualJdbcTarget(ProductTypeEnum product) {
        assertEquals(5432, DiagnosticEndpoint.parse(product,
                "jdbc:postgresql://localhost/demo").orElseThrow().port());
    }

    @Test
    void nativeSingleTargetJdbcProtocolsAreResolved() {
        assertEquals(1521, DiagnosticEndpoint.parse(ProductTypeEnum.ORACLE,
                "jdbc:oracle:thin:@localhost:1521:XE").orElseThrow().port());
        assertEquals(1433, DiagnosticEndpoint.parse(ProductTypeEnum.SQLSERVER,
                "jdbc:sqlserver://localhost:1433;DatabaseName=demo").orElseThrow().port());
        assertEquals(8123, DiagnosticEndpoint.parse(ProductTypeEnum.CLICKHOUSE,
                "jdbc:clickhouse://localhost:8123/demo").orElseThrow().port());
        assertEquals(10000, DiagnosticEndpoint.parse(ProductTypeEnum.HIVE,
                "jdbc:hive2://localhost:10000/demo").orElseThrow().port());
        assertEquals(6041, DiagnosticEndpoint.parse(ProductTypeEnum.TDENGINE,
                "jdbc:TAOS-WS://localhost:6041/demo").orElseThrow().port());
    }

    @Test
    void defaultsComeFromJdbcProtocolRatherThanProductUiDefaults() {
        assertEquals(3306, DiagnosticEndpoint.parse(ProductTypeEnum.DORIS,
                "jdbc:mysql://localhost/demo").orElseThrow().port());
        assertEquals(3306, DiagnosticEndpoint.parse(ProductTypeEnum.STARROCKS,
                "jdbc:mysql://localhost/demo").orElseThrow().port());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.DORIS,
                "jdbc:postgresql://localhost:9030/demo").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.MYSQL,
                "jdbc:mysql://localhost:65536/demo").isEmpty());
    }

    @Test
    void transportDependentOrRequiredPortsMustNotBeGuessed() {
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.CLICKHOUSE,
                "jdbc:clickhouse://localhost/demo?ssl=true").isEmpty());
        assertEquals(9443, DiagnosticEndpoint.parse(ProductTypeEnum.CLICKHOUSE,
                "jdbc:clickhouse://localhost:9443/demo?ssl=true").orElseThrow().port());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.INFLUXDB3,
                "jdbc:arrow-flight-sql://localhost/?useEncryption=0").isEmpty());
        assertEquals(8181, DiagnosticEndpoint.parse(ProductTypeEnum.INFLUXDB3,
                "jdbc:arrow-flight-sql://localhost:8181/?useEncryption=0").orElseThrow().port());
    }

    @Test
    void ambiguousRoutesAndCustomTransportsRemainUnverified() {
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.DORIS,
                "jdbc:mysql://localhost:9030/demo?socketFactory=custom").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.MYSQL,
                "jdbc:mysql://localhost,other/demo").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.ORACLE,
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=localhost)(PORT=1521)))").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.SQLSERVER,
                "jdbc:sqlserver://localhost;DatabaseName=demo;failoverPartner=other").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.HIVE,
                "jdbc:hive2://localhost:2181/demo;serviceDiscoveryMode=zooKeeper").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.SQLITE3, "jdbc:sqlite::memory:").isEmpty());
    }
}
