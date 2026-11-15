package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DS_DATASOURCE_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.*;

class DevDsDatasourceAdapterTest {

    /** 受管 SQL 校验与调度器适配必须覆盖同一批产品：只接一侧会让任务能保存却无法提交，或留下永远走不到的映射。 */
    @ParameterizedTest
    @EnumSource(ProductTypeEnum.class)
    void schedulableProductsAreExactlyThoseCoveredByManagedSqlAuthorization(ProductTypeEnum product) {
        boolean authorized = ManagedSchemaScriptPlan.supports(product) || SqlStatementClassifier.supports(product);
        assertEquals(authorized, DevDsDatasourceAdapter.supports(product), product.name());
    }

    @Test
    void schedulableProductsUseDataSourceProductIds() {
        assertEquals(List.of("DM", "MARIADB", "MYSQL", "OPENGAUSS", "ORACLE", "POSTGRESQL", "TIDB"),
                DevDsDatasourceAdapter.schedulableProducts());
    }

    @Test
    void oracleSidAndServiceNameKeepTheirConnectType() {
        var sid = DevDsDatasourceAdapter.target(ProductTypeEnum.ORACLE,
                "jdbc:oracle:thin:@db.example:1521:review", "review", "APP");
        assertEquals(new DevDsDatasourceAdapter.Target("ORACLE", "db.example", 1521, "review",
                DevDsDatasourceAdapter.ORACLE_SID, Map.of()), sid);
        var service = DevDsDatasourceAdapter.target(ProductTypeEnum.ORACLE,
                "jdbc:oracle:thin:@//db.example:1522/review.pdb", "review", "APP");
        assertEquals(new DevDsDatasourceAdapter.Target("ORACLE", "db.example", 1522, "review.pdb",
                DevDsDatasourceAdapter.ORACLE_SERVICE_NAME, Map.of()), service);
        var ezConnect = DevDsDatasourceAdapter.target(ProductTypeEnum.ORACLE,
                "jdbc:oracle:thin:@db.example/review", "review", "APP");
        assertEquals(1521, ezConnect.port());
        assertEquals(DevDsDatasourceAdapter.ORACLE_SERVICE_NAME, ezConnect.connectType());
    }

    @Test
    void mysqlFamilyDecodesParametersAndFallsBackToTheCatalogNamespace() {
        var mysql = DevDsDatasourceAdapter.target(ProductTypeEnum.MYSQL,
                "jdbc:mysql://db.example:3307/sales?useSSL=false&serverTimezone=GMT%2B8", "sales", "sales");
        assertEquals(new DevDsDatasourceAdapter.Target("MYSQL", "db.example", 3307, "sales", null,
                Map.of("useSSL", "false", "serverTimezone", "GMT+8")), mysql);
        var mariadb = DevDsDatasourceAdapter.target(ProductTypeEnum.MARIADB,
                "jdbc:mariadb://db.example/", "sales", "sales");
        assertEquals("MYSQL", mariadb.type());
        assertEquals(3306, mariadb.port());
        assertEquals("sales", mariadb.database());
        assertEquals(4000, DevDsDatasourceAdapter.target(ProductTypeEnum.TIDB,
                "jdbc:mysql://tidb.example/sales", "sales", "sales").port());
    }

    @Test
    void postgresqlFamilyNeverTreatsTheSchemaAsTheDatabase() {
        var withPath = DevDsDatasourceAdapter.target(ProductTypeEnum.POSTGRESQL,
                "jdbc:postgresql://db.example:5433/review?currentSchema=ods&sslmode=disable", "review", "ods");
        assertEquals(new DevDsDatasourceAdapter.Target("POSTGRESQL", "db.example", 5433, "review", null,
                Map.of("currentSchema", "ods", "sslmode", "disable")), withPath);
        var withoutPath = DevDsDatasourceAdapter.target(ProductTypeEnum.OPENGAUSS,
                "jdbc:opengauss://db.example", "review", "ods");
        assertEquals("POSTGRESQL", withoutPath.type());
        assertEquals("review", withoutPath.database());
        assertEquals(Map.of("currentSchema", "ods"), withoutPath.other());
        var missing = assertThrows(ServiceException.class, () -> DevDsDatasourceAdapter.target(
                ProductTypeEnum.POSTGRESQL, "jdbc:postgresql://db.example:5432/", "", "ods"));
        assertEquals(DEV_DS_DATASOURCE_UNSUPPORTED.getCode(), missing.getCode());
    }

    @Test
    void damengTemplateWithoutDatabaseUsesTheSchema() {
        assertEquals(new DevDsDatasourceAdapter.Target("DAMENG", "dm.example", 5236, "APP", null, Map.of()),
                DevDsDatasourceAdapter.target(ProductTypeEnum.DM, "jdbc:dm://dm.example:5236", null, "APP"));
    }

    /** 多主机、负载均衡、TNS 等地址不能截断成单主机参数，否则调度器可能连到平台未校验的目标。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://h1:3306,h2:3306/sales",
            "jdbc:mysql:loadbalance://h1:3306,h2:3306/sales",
            "jdbc:mysql://[::1]:3306/sales",
            "jdbc:mysql://db.example:70000/sales",
            "jdbc:mysql://db.example:3306/sales?password=%ZZ",
            "jdbc:postgresql://h1:5432,h2:5432/review",
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=a)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=x)))",
            "jdbc:oracle:thin:@review_tns_alias",
            ""
    })
    void addressesThatCannotMapOneToOneAreRejectedWithoutEchoingTheUrl(String url) {
        ProductTypeEnum product = url.startsWith("jdbc:postgresql") ? ProductTypeEnum.POSTGRESQL
                : url.startsWith("jdbc:oracle") ? ProductTypeEnum.ORACLE : ProductTypeEnum.MYSQL;
        var error = assertThrows(ServiceException.class,
                () -> DevDsDatasourceAdapter.target(product, url, "sales", "sales"));
        assertEquals(DEV_DS_DATASOURCE_UNSUPPORTED.getCode(), error.getCode());
        assertFalse(error.getMessage().contains("h2"));
        assertFalse(error.getMessage().contains("password"));
        assertFalse(error.getMessage().contains("DESCRIPTION"));
    }

    @Test
    void productsWithoutManagedSqlAuthorizationAreRejectedByName() {
        var error = assertThrows(ServiceException.class, () -> DevDsDatasourceAdapter.target(
                ProductTypeEnum.CLICKHOUSE, "jdbc:clickhouse://ch.example:8123/ods", "ods", "ods"));
        assertEquals(DEV_DS_DATASOURCE_UNSUPPORTED.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("CLICKHOUSE"));
        assertThrows(ServiceException.class, () -> DevDsDatasourceAdapter.requireSchedulable(null));
    }

}
