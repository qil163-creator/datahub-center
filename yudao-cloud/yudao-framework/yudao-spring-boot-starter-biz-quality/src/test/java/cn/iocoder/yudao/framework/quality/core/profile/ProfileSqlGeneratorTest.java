package cn.iocoder.yudao.framework.quality.core.profile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据探查 SQL 黄金用例（每个支持产品一组）、注入用例与结果读取。
 */
class ProfileSqlGeneratorTest {

    private static final DbSwitchProductRegistry PRODUCTS = DbSwitchProductRegistry.discover();
    private final ProfileSqlGenerator generator = new ProfileSqlGenerator(PRODUCTS);

    private static final ProfileColumn NAME = new ProfileColumn("name", ProfileColumnKind.TEXT);
    private static final ProfileColumn AMOUNT = new ProfileColumn("amount", ProfileColumnKind.NUMERIC);

    private static ProfileRequest request(ProductTypeEnum product, String schema, String table,
            List<ProfileColumn> columns) {
        return new ProfileRequest(product, schema, table, columns, ProfileRequest.DEFAULT_SAMPLE_ROWS,
                ProfileRequest.DEFAULT_TOP_N);
    }

    // ========== 黄金用例 ==========

    @Test
    void postgresqlGoldenCoversEveryKind() {
        ProfileRequest request = request(ProductTypeEnum.POSTGRESQL, "sales", "orders", List.of(NAME, AMOUNT,
                new ProfileColumn("created_at", ProfileColumnKind.TEMPORAL),
                new ProfileColumn("active", ProfileColumnKind.BOOLEAN),
                new ProfileColumn("payload", ProfileColumnKind.OTHER)));

        assertThat(generator.buildTotalCountSql(request))
                .isEqualTo("SELECT COUNT(*) AS p_rows FROM \"sales\".\"orders\"");
        ProfileAggregatePlan plan = generator.buildAggregate(request);
        assertThat(plan.sql()).isEqualTo("SELECT COUNT(*) AS p_rows, "
                + "COUNT(\"name\") AS c0_nn, COUNT(DISTINCT \"name\") AS c0_dc, MIN(\"name\") AS c0_min, "
                + "MAX(\"name\") AS c0_max, AVG(LENGTH(\"name\")) AS c0_avglen, MIN(LENGTH(\"name\")) AS c0_minlen, "
                + "MAX(LENGTH(\"name\")) AS c0_maxlen, "
                + "COUNT(\"amount\") AS c1_nn, COUNT(DISTINCT \"amount\") AS c1_dc, MIN(\"amount\") AS c1_min, "
                + "MAX(\"amount\") AS c1_max, AVG(\"amount\") AS c1_avg, "
                + "COUNT(\"created_at\") AS c2_nn, COUNT(DISTINCT \"created_at\") AS c2_dc, "
                + "MIN(\"created_at\") AS c2_min, MAX(\"created_at\") AS c2_max, "
                + "COUNT(\"active\") AS c3_nn, COUNT(DISTINCT \"active\") AS c3_dc, "
                + "COUNT(\"payload\") AS c4_nn "
                + "FROM (SELECT \"name\", \"amount\", \"created_at\", \"active\", \"payload\" "
                + "FROM \"sales\".\"orders\" LIMIT 100000) p_sample");
        assertThat(plan.expectedColumnCount()).isEqualTo(20);

        List<ProfileTopValuesQuery> top = generator.buildTopValues(request);
        assertThat(top).extracting(query -> query.column().name())
                .containsExactly("name", "amount", "created_at", "active");
        assertThat(top.get(0).sql()).isEqualTo("SELECT \"name\" AS p_value, COUNT(*) AS p_count "
                + "FROM (SELECT \"name\" FROM \"sales\".\"orders\" LIMIT 100000) p_sample "
                + "WHERE \"name\" IS NOT NULL GROUP BY \"name\" ORDER BY p_count DESC, p_value ASC LIMIT 10");
    }

    @Test
    void postgresFamilyUsesDoubleQuotesAndLimit() {
        for (ProductTypeEnum product : List.of(ProductTypeEnum.KINGBASE, ProductTypeEnum.GREENPLUM,
                ProductTypeEnum.HIGHGO, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.UXDB)) {
            ProfileRequest request = request(product, "s", "t", List.of(NAME));
            assertThat(generator.buildAggregate(request).sql()).as(product.name()).isEqualTo(
                    "SELECT COUNT(*) AS p_rows, COUNT(\"name\") AS c0_nn, COUNT(DISTINCT \"name\") AS c0_dc, "
                            + "MIN(\"name\") AS c0_min, MAX(\"name\") AS c0_max, AVG(LENGTH(\"name\")) AS c0_avglen, "
                            + "MIN(LENGTH(\"name\")) AS c0_minlen, MAX(LENGTH(\"name\")) AS c0_maxlen "
                            + "FROM (SELECT \"name\" FROM \"s\".\"t\" LIMIT 100000) p_sample");
        }
    }

    @Test
    void mysqlGoldenUsesBackticksAndCharLength() {
        ProfileRequest request = request(ProductTypeEnum.MYSQL, "shop", "orders", List.of(NAME, AMOUNT));
        assertThat(generator.buildTotalCountSql(request)).isEqualTo("SELECT COUNT(*) AS p_rows FROM `shop`.`orders`");
        assertThat(generator.buildAggregate(request).sql()).isEqualTo("SELECT COUNT(*) AS p_rows, "
                + "COUNT(`name`) AS c0_nn, COUNT(DISTINCT `name`) AS c0_dc, MIN(`name`) AS c0_min, "
                + "MAX(`name`) AS c0_max, AVG(CHAR_LENGTH(`name`)) AS c0_avglen, "
                + "MIN(CHAR_LENGTH(`name`)) AS c0_minlen, MAX(CHAR_LENGTH(`name`)) AS c0_maxlen, "
                + "COUNT(`amount`) AS c1_nn, COUNT(DISTINCT `amount`) AS c1_dc, MIN(`amount`) AS c1_min, "
                + "MAX(`amount`) AS c1_max, AVG(`amount`) AS c1_avg "
                + "FROM (SELECT `name`, `amount` FROM `shop`.`orders` LIMIT 100000) p_sample");
        assertThat(generator.buildTopValues(request, AMOUNT).sql()).isEqualTo(
                "SELECT `amount` AS p_value, COUNT(*) AS p_count "
                        + "FROM (SELECT `amount` FROM `shop`.`orders` LIMIT 100000) p_sample "
                        + "WHERE `amount` IS NOT NULL GROUP BY `amount` ORDER BY p_count DESC, p_value ASC LIMIT 10");
    }

    @Test
    void mysqlFamilyUsesBackticksAndCharLength() {
        for (ProductTypeEnum product : List.of(ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB,
                ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS)) {
            ProfileRequest request = request(product, "db", "t", List.of(NAME));
            assertThat(generator.buildAggregate(request).sql()).as(product.name()).isEqualTo(
                    "SELECT COUNT(*) AS p_rows, COUNT(`name`) AS c0_nn, COUNT(DISTINCT `name`) AS c0_dc, "
                            + "MIN(`name`) AS c0_min, MAX(`name`) AS c0_max, AVG(CHAR_LENGTH(`name`)) AS c0_avglen, "
                            + "MIN(CHAR_LENGTH(`name`)) AS c0_minlen, MAX(CHAR_LENGTH(`name`)) AS c0_maxlen "
                            + "FROM (SELECT `name` FROM `db`.`t` LIMIT 100000) p_sample");
        }
    }

    @Test
    void oracleGoldenUsesExactQuotedNamesAndFetchFirst() {
        ProfileRequest request = request(ProductTypeEnum.ORACLE, "HR", "Orders", List.of(NAME, AMOUNT));
        assertThat(generator.buildTotalCountSql(request)).isEqualTo("SELECT COUNT(*) AS p_rows FROM \"HR\".\"Orders\"");
        assertThat(generator.buildAggregate(request).sql()).isEqualTo("SELECT COUNT(*) AS p_rows, "
                + "COUNT(\"name\") AS c0_nn, COUNT(DISTINCT \"name\") AS c0_dc, MIN(\"name\") AS c0_min, "
                + "MAX(\"name\") AS c0_max, AVG(LENGTH(\"name\")) AS c0_avglen, MIN(LENGTH(\"name\")) AS c0_minlen, "
                + "MAX(LENGTH(\"name\")) AS c0_maxlen, "
                + "COUNT(\"amount\") AS c1_nn, COUNT(DISTINCT \"amount\") AS c1_dc, MIN(\"amount\") AS c1_min, "
                + "MAX(\"amount\") AS c1_max, AVG(\"amount\") AS c1_avg "
                + "FROM (SELECT \"name\", \"amount\" FROM \"HR\".\"Orders\" FETCH FIRST 100000 ROWS ONLY) p_sample");
        assertThat(generator.buildTopValues(request, NAME).sql()).isEqualTo(
                "SELECT \"name\" AS p_value, COUNT(*) AS p_count "
                        + "FROM (SELECT \"name\" FROM \"HR\".\"Orders\" FETCH FIRST 100000 ROWS ONLY) p_sample "
                        + "WHERE \"name\" IS NOT NULL GROUP BY \"name\" ORDER BY p_count DESC, p_value ASC "
                        + "FETCH FIRST 10 ROWS ONLY");
    }

    @Test
    void damengGoldenFoldsOrdinaryNamesToUpperCase() {
        ProfileRequest request = request(ProductTypeEnum.DM, "sysdba", "orders", List.of(NAME, AMOUNT));
        assertThat(generator.buildTotalCountSql(request)).isEqualTo("SELECT COUNT(*) AS p_rows FROM \"SYSDBA\".\"ORDERS\"");
        assertThat(generator.buildAggregate(request).sql()).isEqualTo("SELECT COUNT(*) AS p_rows, "
                + "COUNT(\"NAME\") AS c0_nn, COUNT(DISTINCT \"NAME\") AS c0_dc, MIN(\"NAME\") AS c0_min, "
                + "MAX(\"NAME\") AS c0_max, AVG(LENGTH(\"NAME\")) AS c0_avglen, MIN(LENGTH(\"NAME\")) AS c0_minlen, "
                + "MAX(LENGTH(\"NAME\")) AS c0_maxlen, "
                + "COUNT(\"AMOUNT\") AS c1_nn, COUNT(DISTINCT \"AMOUNT\") AS c1_dc, MIN(\"AMOUNT\") AS c1_min, "
                + "MAX(\"AMOUNT\") AS c1_max, AVG(\"AMOUNT\") AS c1_avg "
                + "FROM (SELECT \"NAME\", \"AMOUNT\" FROM \"SYSDBA\".\"ORDERS\" LIMIT 100000) p_sample");
        assertThat(generator.buildTopValues(request, NAME).sql()).isEqualTo(
                "SELECT \"NAME\" AS p_value, COUNT(*) AS p_count "
                        + "FROM (SELECT \"NAME\" FROM \"SYSDBA\".\"ORDERS\" LIMIT 100000) p_sample "
                        + "WHERE \"NAME\" IS NOT NULL GROUP BY \"NAME\" ORDER BY p_count DESC, p_value ASC LIMIT 10");
    }

    @Test
    void hiveGoldenUsesBackticksAndLength() {
        ProfileRequest request = request(ProductTypeEnum.HIVE, "ods", "orders", List.of(NAME, AMOUNT));
        assertThat(generator.buildTotalCountSql(request)).isEqualTo("SELECT COUNT(*) AS p_rows FROM `ods`.`orders`");
        assertThat(generator.buildAggregate(request).sql()).isEqualTo("SELECT COUNT(*) AS p_rows, "
                + "COUNT(`name`) AS c0_nn, COUNT(DISTINCT `name`) AS c0_dc, MIN(`name`) AS c0_min, "
                + "MAX(`name`) AS c0_max, AVG(LENGTH(`name`)) AS c0_avglen, MIN(LENGTH(`name`)) AS c0_minlen, "
                + "MAX(LENGTH(`name`)) AS c0_maxlen, "
                + "COUNT(`amount`) AS c1_nn, COUNT(DISTINCT `amount`) AS c1_dc, MIN(`amount`) AS c1_min, "
                + "MAX(`amount`) AS c1_max, AVG(`amount`) AS c1_avg "
                + "FROM (SELECT `name`, `amount` FROM `ods`.`orders` LIMIT 100000) p_sample");
        assertThat(generator.buildTopValues(request, NAME).sql()).isEqualTo(
                "SELECT `name` AS p_value, COUNT(*) AS p_count "
                        + "FROM (SELECT `name` FROM `ods`.`orders` LIMIT 100000) p_sample "
                        + "WHERE `name` IS NOT NULL GROUP BY `name` ORDER BY p_count DESC, p_value ASC LIMIT 10");
    }

    @Test
    void blankSchemaReferencesTableOnlyAndCustomBoundsAreApplied() {
        ProfileRequest request = new ProfileRequest(ProductTypeEnum.POSTGRESQL, "", "t", List.of(AMOUNT), 500, 3);
        assertThat(generator.buildTopValues(request, AMOUNT).sql()).isEqualTo(
                "SELECT \"amount\" AS p_value, COUNT(*) AS p_count FROM (SELECT \"amount\" FROM \"t\" LIMIT 500) "
                        + "p_sample WHERE \"amount\" IS NOT NULL GROUP BY \"amount\" ORDER BY p_count DESC, "
                        + "p_value ASC LIMIT 3");
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"SQLSERVER", "GBASE8A", "CLICKHOUSE", "OCEANBASE", "DB2",
            "SQLITE3", "TDENGINE", "OSCAR"})
    void unsupportedProductsAreRejected(ProductTypeEnum product) {
        ProfileRequest request = request(product, "s", "t", List.of(NAME));
        assertThat(ProfileSqlGenerator.supports(product)).isFalse();
        assertThatThrownBy(() -> generator.buildAggregate(request)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> generator.buildTotalCountSql(request))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> generator.buildTopValues(request)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void otherColumnsHaveNoTopValues() {
        ProfileColumn blob = new ProfileColumn("doc", ProfileColumnKind.OTHER);
        ProfileRequest request = request(ProductTypeEnum.POSTGRESQL, "s", "t", List.of(blob));
        assertThat(generator.buildTopValues(request)).isEmpty();
        assertThatThrownBy(() -> generator.buildTopValues(request, blob)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.buildTopValues(request, NAME)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========== 注入用例 ==========

    @Test
    void postgresQuoteEscapeIsDoubledInsideIdentifiers() {
        ProfileColumn evil = new ProfileColumn("a\" ) FROM pg_shadow --", ProfileColumnKind.TEXT);
        ProfileRequest request = request(ProductTypeEnum.POSTGRESQL, "public",
                "orders\"; DROP TABLE x; --", List.of(evil));
        String sql = generator.buildAggregate(request).sql();
        assertThat(sql).contains("\"public\".\"orders\"\"; DROP TABLE x; --\"")
                .contains("COUNT(\"a\"\" ) FROM pg_shadow --\")");
        // 去掉所有合法引用的标识符后，SQL 只剩生成器自身的关键字
        assertThat(stripQuoted(sql, '"')).doesNotContain("DROP").doesNotContain("pg_shadow").doesNotContain("--");
        assertThat(stripQuoted(generator.buildTopValues(request, evil).sql(), '"')).doesNotContain("pg_shadow");
    }

    @Test
    void mysqlBacktickEscapeIsDoubledAndBackslashStaysInsideIdentifier() {
        ProfileColumn evil = new ProfileColumn("a`) FROM mysql.user #", ProfileColumnKind.TEXT);
        ProfileColumn slash = new ProfileColumn("b\\'", ProfileColumnKind.NUMERIC);
        ProfileRequest request = request(ProductTypeEnum.MYSQL, "db", "t`; DROP TABLE x", List.of(evil, slash));
        String sql = generator.buildAggregate(request).sql();
        assertThat(sql).contains("`db`.`t``; DROP TABLE x`").contains("COUNT(`a``) FROM mysql.user #`)")
                .contains("COUNT(`b\\'`)");
        assertThat(stripQuoted(sql, '`')).doesNotContain("DROP").doesNotContain("mysql.user").doesNotContain("#")
                .doesNotContain("'");
    }

    @Test
    void productsWithoutEscapableQuotesRejectMaliciousNames() {
        String[] evilNames = {"a\"b", "a b", "x;DROP TABLE t", "a`b", "", "a\u0000b"};
        for (ProductTypeEnum product : List.of(ProductTypeEnum.DM, ProductTypeEnum.HIVE, ProductTypeEnum.KINGBASE,
                ProductTypeEnum.STARROCKS)) {
            for (String name : evilNames) {
                if (name.isEmpty()) {
                    assertThatThrownBy(() -> new ProfileColumn(name, ProfileColumnKind.TEXT))
                            .isInstanceOf(IllegalArgumentException.class);
                    continue;
                }
                ProfileRequest byColumn = request(product, "s", "t",
                        List.of(new ProfileColumn(name, ProfileColumnKind.TEXT)));
                assertThatThrownBy(() -> generator.buildAggregate(byColumn)).as(product + " column " + name)
                        .isInstanceOf(IllegalArgumentException.class);
                ProfileRequest byTable = request(product, "s", name, List.of(NAME));
                assertThatThrownBy(() -> generator.buildTotalCountSql(byTable)).as(product + " table " + name)
                        .isInstanceOf(IllegalArgumentException.class);
                ProfileRequest bySchema = request(product, name, "t", List.of(NAME));
                assertThatThrownBy(() -> generator.buildAggregate(bySchema)).as(product + " schema " + name)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        // Oracle 精确名称允许空格，但拒绝双引号与 NUL
        for (String name : List.of("a\"b", "x\" FROM dual --", "a\u0000b")) {
            ProfileRequest request = request(ProductTypeEnum.ORACLE, "HR", "T",
                    List.of(new ProfileColumn(name, ProfileColumnKind.TEXT)));
            assertThatThrownBy(() -> generator.buildAggregate(request)).isInstanceOf(IllegalArgumentException.class);
        }
        // PostgreSQL 拒绝 NUL 与超长（> 63 字节）标识符，避免服务端截断成另一名称
        for (String name : List.of("a\u0000b", "x".repeat(64))) {
            ProfileRequest request = request(ProductTypeEnum.POSTGRESQL, "s", name, List.of(NAME));
            assertThatThrownBy(() -> generator.buildTotalCountSql(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========== 请求边界 ==========

    @Test
    void requestBoundsAreValidated() {
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", List.of(NAME), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", List.of(NAME), 1_000_001, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", List.of(NAME), 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", List.of(NAME, NAME), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", List.of(), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        List<ProfileColumn> many = new ArrayList<>();
        for (int i = 0; i <= ProfileRequest.MAX_COLUMNS; i++) {
            many.add(new ProfileColumn("c" + i, ProfileColumnKind.TEXT));
        }
        assertThatThrownBy(() -> new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", many, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProfileRequest(ProductTypeEnum.MYSQL, "s", "t", many.subList(0, 100), 10, 10).columns())
                .hasSize(100);
    }

    // ========== 类型推断 ==========

    @Test
    void columnKindIsInferredConservatively() {
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.MYSQL, "VARCHAR(64)")).isEqualTo(ProfileColumnKind.TEXT);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.MYSQL, "bigint(20) unsigned"))
                .isEqualTo(ProfileColumnKind.NUMERIC);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "character varying"))
                .isEqualTo(ProfileColumnKind.TEXT);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "timestamp(6) with time zone"))
                .isEqualTo(ProfileColumnKind.TEMPORAL);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "bool")).isEqualTo(ProfileColumnKind.BOOLEAN);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "jsonb")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "uuid")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "int4[]")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.POSTGRESQL, "text")).isEqualTo(ProfileColumnKind.TEXT);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.ORACLE, "NUMBER(10,2)")).isEqualTo(ProfileColumnKind.NUMERIC);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.ORACLE, "VARCHAR2(100)")).isEqualTo(ProfileColumnKind.TEXT);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.ORACLE, "CLOB")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.DM, "TEXT")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.HIVE, "string")).isEqualTo(ProfileColumnKind.TEXT);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.HIVE, "array<string>")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.HIVE, "map<string,int>")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.MYSQL, "blob")).isEqualTo(ProfileColumnKind.OTHER);
        assertThat(ProfileColumnKind.fromTypeName(ProductTypeEnum.MYSQL, null)).isEqualTo(ProfileColumnKind.OTHER);
    }

    // ========== 结果读取 ==========

    @Test
    void aggregateResultIsReadByPositionAndValuesAreTruncated() throws Exception {
        ProfileRequest request = request(ProductTypeEnum.POSTGRESQL, "s", "t", List.of(NAME, AMOUNT));
        ProfileAggregatePlan plan = generator.buildAggregate(request);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(13);
        String longValue = "字".repeat(300);
        when(rs.getLong(anyInt())).thenAnswer(invocation -> switch ((int) invocation.getArgument(0)) {
            case 1 -> 100L;
            case 2 -> 90L;
            case 3 -> 12L;
            case 7 -> 2L;
            case 8 -> 300L;
            case 9 -> 100L;
            case 10 -> 100L;
            default -> 0L;
        });
        when(rs.getString(anyInt())).thenAnswer(invocation -> switch ((int) invocation.getArgument(0)) {
            case 4 -> "a";
            case 5 -> longValue;
            case 6 -> "5.5000000000000000";
            case 11 -> "1";
            case 12 -> "100";
            case 13 -> "NaN";
            default -> null;
        });
        ProfileAggregatePlan.ProfileAggregateResult result = plan.read(rs);
        assertThat(result.sampledRows()).isEqualTo(100L);
        ProfileColumnStats name = result.columns().get(0);
        assertThat(name.nonNullCount()).isEqualTo(90L);
        assertThat(name.distinctCount()).isEqualTo(12L);
        assertThat(name.minValue()).isEqualTo("a");
        assertThat(name.maxValue()).hasSize(ProfileSqlGenerator.MAX_VALUE_LENGTH);
        assertThat(name.avgLength()).isEqualByComparingTo(new BigDecimal("5.5"));
        assertThat(name.minLength()).isEqualTo(2L);
        assertThat(name.maxLength()).isEqualTo(300L);
        ProfileColumnStats amount = result.columns().get(1);
        assertThat(amount.nonNullCount()).isEqualTo(100L);
        assertThat(amount.distinctCount()).isEqualTo(100L);
        assertThat(amount.minValue()).isEqualTo("1");
        assertThat(amount.maxValue()).isEqualTo("100");
        assertThat(amount.avgValue()).isNull();
        assertThat(amount.avgLength()).isNull();

        when(meta.getColumnCount()).thenReturn(12);
        assertThatThrownBy(() -> plan.read(rs)).isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void topValuesAreBoundedAndTruncatedBySurrogateSafeCodePoints() throws Exception {
        ProfileRequest request = new ProfileRequest(ProductTypeEnum.POSTGRESQL, "s", "t", List.of(NAME), 10, 2);
        ProfileTopValuesQuery query = generator.buildTopValues(request, NAME);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true);
        String emoji = "😀".repeat(300);
        when(rs.getString(1)).thenReturn(emoji, "b", "c");
        when(rs.getLong(2)).thenReturn(5L, 3L, 1L);
        List<ProfileTopValuesQuery.ValueCount> values = query.read(rs);
        assertThat(values).hasSize(2);
        assertThat(values.get(0).value().codePointCount(0, values.get(0).value().length()))
                .isEqualTo(ProfileSqlGenerator.MAX_VALUE_LENGTH);
        assertThat(values.get(1)).isEqualTo(new ProfileTopValuesQuery.ValueCount("b", 3L));
    }

    /**
     * 去掉按给定引号包裹的标识符（支持双写转义），返回剩余 SQL 文本。
     */
    private static String stripQuoted(String sql, char quote) {
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == quote) {
                if (inside && i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i++;
                    continue;
                }
                inside = !inside;
                continue;
            }
            if (!inside) {
                out.append(ch);
            }
        }
        return out.toString();
    }

}
