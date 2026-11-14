package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合并写入的事务性测试（真实 PostgreSQL）：写入失败时正式表保持合并前的内容
 */
@Testcontainers
class FillMergeJdbcWriterPostgreSqlTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new DataforgePostgreSqlContainer();

    private static final List<String> FIELDS = List.of("id", "amount", "qty", "report_date", "happen_time",
            "clock", "remark", "source_org_id", "period_key", "source_submission_id", "source_row_id");

    private static final FillMergeJdbcWriter.Target TARGET =
            new FillMergeJdbcWriter.Target(ProductTypeEnum.POSTGRESQL, "public", "fill_target");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS fill_target");
        jdbc.execute("""
                CREATE TABLE fill_target (
                  id varchar(32) PRIMARY KEY,
                  amount numeric(12,2),
                  qty integer,
                  report_date date,
                  happen_time timestamp,
                  clock time,
                  remark varchar(10),
                  source_org_id bigint,
                  period_key varchar(32),
                  source_submission_id bigint,
                  source_row_id varchar(64)
                )""");
        // 合并前的正式数据：组织 1 两行、组织 2 一行，同一所属期
        jdbc.update("INSERT INTO fill_target VALUES ('a', 1.00, 1, '2026-10-01', NULL, NULL, '旧', 1, '2026-10', 100, 'r1')");
        jdbc.update("INSERT INTO fill_target VALUES ('b', 2.00, 2, '2026-10-02', NULL, NULL, '旧', 1, '2026-10', 100, 'r2')");
        jdbc.update("INSERT INTO fill_target VALUES ('z', 9.00, 9, '2026-10-09', NULL, NULL, '他组织', 2, '2026-10', 200, 'r9')");
    }

    private static Object[] row(String id, String remark, long orgId, long submissionId) {
        return new Object[]{id, new BigDecimal("12.50"), 7, LocalDate.of(2026, 10, 15),
                LocalDateTime.of(2026, 10, 15, 8, 30, 0), LocalTime.of(8, 30), remark,
                orgId, "2026-10", submissionId, "row-" + id};
    }

    private List<Map<String, Object>> rowsOfOrg(long orgId) {
        return jdbc.queryForList("SELECT * FROM fill_target WHERE source_org_id = ? ORDER BY id", orgId);
    }

    @Test
    void onlyTransactionalProductsUseThisWriter() {
        assertTrue(FillMergeJdbcWriter.supports(ProductTypeEnum.POSTGRESQL));
        assertTrue(FillMergeJdbcWriter.supports(ProductTypeEnum.MYSQL));
        assertFalse(FillMergeJdbcWriter.supports(ProductTypeEnum.CLICKHOUSE));
        assertFalse(FillMergeJdbcWriter.supports(ProductTypeEnum.MONGODB));
        assertFalse(FillMergeJdbcWriter.supports(null));
    }

    @Test
    void replaceScopeSwapsOnlyTheScopeAndBindsTypes() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("c", "新", 1, 101));
        Object[] withNulls = row("d", null, 1, 101);
        withNulls[1] = null;
        withNulls[3] = null;
        rows.add(withNulls);

        int written = FillMergeJdbcWriter.replaceScope(dataSource, TARGET, FIELDS, rows,
                List.of("source_org_id", "period_key"), List.of(1L, "2026-10"));

        assertEquals(2, written);
        List<Map<String, Object>> org1 = rowsOfOrg(1);
        assertEquals(List.of("c", "d"), org1.stream().map(item -> item.get("id")).toList());
        assertEquals(new BigDecimal("12.50"), org1.get(0).get("amount"));
        assertEquals(java.sql.Date.valueOf("2026-10-15"), org1.get(0).get("report_date"));
        assertEquals(java.sql.Timestamp.valueOf("2026-10-15 08:30:00"), org1.get(0).get("happen_time"));
        assertEquals(java.sql.Time.valueOf("08:30:00"), org1.get(0).get("clock"));
        assertNull(org1.get(1).get("amount"));
        assertNull(org1.get(1).get("report_date"));
        assertNull(org1.get(1).get("remark"));
        // 其他组织的数据不受影响
        assertEquals(1, rowsOfOrg(2).size());
    }

    @Test
    void replaceScopesClearsEveryOrgOfTheSubtreeInOneTransaction() {
        // 多级汇总：顶层组织 1 的提交含下级组织 2 的行，两个组织的旧数据都被替换；组织 3 的数据不受影响
        jdbc.update("INSERT INTO fill_target VALUES ('y', 3.00, 3, '2026-10-03', NULL, NULL, '其他', 3, '2026-10', 300, 'r8')");
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("c", "上级", 1, 101));
        rows.add(row("e", "下级", 2, 101));

        int written = FillMergeJdbcWriter.replaceScopes(dataSource, TARGET, FIELDS, rows,
                List.of("source_org_id", "period_key"), List.of(List.of(1L, "2026-10"), List.of(2L, "2026-10")));

        assertEquals(2, written);
        assertEquals(List.of("c"), rowsOfOrg(1).stream().map(item -> item.get("id")).toList());
        assertEquals(List.of("e"), rowsOfOrg(2).stream().map(item -> item.get("id")).toList());
        assertEquals(1, rowsOfOrg(3).size());
    }

    @Test
    void replaceScopeFailureKeepsDataBeforeMerge() {
        List<Object[]> rows = List.of(row("c", "新", 1, 101),
                row("d", "超过十个字符的备注内容会被目标列拒绝", 1, 101));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FillMergeJdbcWriter.replaceScope(dataSource, TARGET, FIELDS, rows,
                        List.of("source_org_id", "period_key"), List.of(1L, "2026-10")));

        assertTrue(failure.getMessage().contains("未提交"), failure.getMessage());
        // 删除与写入一起回滚：组织 1 仍是合并前的两行
        List<Map<String, Object>> org1 = rowsOfOrg(1);
        assertEquals(List.of("a", "b"), org1.stream().map(item -> item.get("id")).toList());
        assertEquals("旧", org1.get(0).get("remark"));
    }

    @Test
    void replaceScopeWithNoRowsClearsTheScope() {
        // 零上报：范围内数据清空
        assertEquals(0, FillMergeJdbcWriter.replaceScope(dataSource, TARGET, FIELDS, List.of(),
                List.of("source_org_id", "period_key"), List.of(1L, "2026-10")));
        assertTrue(rowsOfOrg(1).isEmpty());
        assertEquals(1, rowsOfOrg(2).size());
    }

    @Test
    void upsertUpdatesExistingKeysAndInsertsTheRest() {
        List<Object[]> rows = List.of(row("a", "已更新", 1, 101), row("n", "新增", 1, 101));

        int written = FillMergeJdbcWriter.upsert(dataSource, TARGET, FIELDS, rows,
                List.of("id", "source_org_id", "period_key"), null);

        assertEquals(2, written);
        List<Map<String, Object>> org1 = rowsOfOrg(1);
        assertEquals(List.of("a", "b", "n"), org1.stream().map(item -> item.get("id")).toList());
        assertEquals("已更新", org1.get(0).get("remark"));
        assertEquals(101L, org1.get(0).get("source_submission_id"));
        assertEquals("旧", org1.get(1).get("remark"));
    }

    @Test
    void upsertFailureRollsBackEarlierUpdates() {
        List<Object[]> rows = List.of(row("a", "已更新", 1, 101),
                row("n", "超过十个字符的备注内容会被目标列拒绝", 1, 101));

        assertThrows(IllegalStateException.class, () -> FillMergeJdbcWriter.upsert(dataSource, TARGET, FIELDS,
                rows, List.of("id", "source_org_id", "period_key"), stale(100L)));

        List<Map<String, Object>> org1 = rowsOfOrg(1);
        assertEquals(List.of("a", "b"), org1.stream().map(item -> item.get("id")).toList());
        assertEquals("旧", org1.get(0).get("remark"));
        assertEquals(100L, org1.get(0).get("source_submission_id"));
    }

    private static FillMergeJdbcWriter.StaleRows stale(Long... previousSubmissionIds) {
        return new FillMergeJdbcWriter.StaleRows(List.of("source_org_id", "period_key"), List.of(1L, "2026-10"),
                "source_submission_id", List.of(previousSubmissionIds));
    }

    @Test
    void upsertRemovesRowsDroppedSinceThePreviousSubmission() {
        // 更正：上次提交（100）写入了 a、b，本次只保留 a 并新增 n；b 不应继续留在正式表
        List<Object[]> rows = List.of(row("a", "已更新", 1, 101), row("n", "新增", 1, 101));

        FillMergeJdbcWriter.upsert(dataSource, TARGET, FIELDS, rows,
                List.of("id", "source_org_id", "period_key"), stale(100L));

        assertEquals(List.of("a", "n"), rowsOfOrg(1).stream().map(item -> item.get("id")).toList());
        // 其他组织、其他来源的数据不受影响
        assertEquals(1, rowsOfOrg(2).size());
    }

    @Test
    void upsertWithNoRowsStillRemovesPreviousSubmissionRows() {
        // 更正为零上报：本次没有数据行，上次提交写入的行全部清理
        assertEquals(0, FillMergeJdbcWriter.upsert(dataSource, TARGET, FIELDS, List.of(),
                List.of("id", "source_org_id", "period_key"), stale(100L)));
        assertTrue(rowsOfOrg(1).isEmpty());
        assertEquals(1, rowsOfOrg(2).size());
    }

}
