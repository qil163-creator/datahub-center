package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.dbswitch.core.service.AggregateQuerySql.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只在显式配置的本地私有 PostgreSQL 夹具上运行，从不连接平台数据库。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/filter_test")
@Timeout(30)
class AggregateQuerySqlPostgreSqlTest {

    private Connection connection;

    private String schema;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), "codex_test", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE agg_orders(region text, pay_status text, amount numeric(30,10), "
                    + "pay_time timestamp)");
            statement.execute("INSERT INTO agg_orders VALUES"
                    + "('east','PAID',10.5,'2026-01-03 10:00'),('east','PAID',0.25,'2026-01-20 11:00'),"
                    + "('east','REFUND',100,'2026-01-21 11:00'),('west','PAID',9007199254740993.1234567891,"
                    + "'2026-02-02 08:00'),('west','PAID',1,'2026-04-01 00:00')");
            try (ResultSet result = statement.executeQuery("SELECT nspname FROM pg_namespace "
                    + "WHERE oid = pg_my_temp_schema()")) {
                result.next();
                schema = result.getString(1);
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void monthlySumKeepsExactDecimalsAndAppliesBusinessCondition() throws Exception {
        var plan = render(new Request(ProductTypeEnum.POSTGRESQL, schema, "agg_orders",
                List.of("region", "pay_status", "amount", "pay_time"), List.of("region"), "amount",
                Aggregation.SUM, new TimeWindow("pay_time", TimeGrain.MONTH, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 4, 1)), List.of(), List.of("pay_status = 'PAID'"), 100));
        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(plan.sql())) {
            for (int index = 0; index < plan.parameters().size(); index++) {
                statement.setObject(index + 1, plan.parameters().get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getString(plan.dimensionAliases().getFirst()) + "|"
                            + result.getDate(plan.timeAlias()).toLocalDate() + "|"
                            + result.getBigDecimal(plan.valueAlias()).stripTrailingZeros().toPlainString());
                }
            }
        }
        assertEquals(List.of("east|2026-01-01|10.75", "west|2026-02-01|9007199254740993.1234567891"), rows);
        assertEquals(0, new BigDecimal("10.75").compareTo(new BigDecimal(rows.getFirst().split("\\|")[2])));
    }
}
