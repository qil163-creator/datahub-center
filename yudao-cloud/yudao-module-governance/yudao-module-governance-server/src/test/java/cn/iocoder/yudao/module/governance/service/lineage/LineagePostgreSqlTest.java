package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageColumnGraphReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageGraphReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageImpactReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageNodeRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageNodeSearchReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageSourcePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableKey;
import cn.iocoder.yudao.module.governance.support.StandardPostgreSqlIntegrationTestBase;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static cn.iocoder.yudao.module.governance.service.lineage.LineageTestReports.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL：0273/0274 迁移重放、来源行锁、ON CONFLICT、物理删除、行值 IN 图查询与元数据大小写匹配。
 *
 * <p>正常路径用 Testcontainers；无 Docker 且未显式指定外部测试库时整类跳过（跳过不等于通过）。</p>
 */
@EnabledIf("postgresAvailable")
@ContextConfiguration(classes = LineagePostgreSqlTest.LineageConfig.class)
@Timeout(120)
class LineagePostgreSqlTest extends StandardPostgreSqlIntegrationTestBase {

    @Autowired
    private LineageReportService reportService;
    @Autowired
    private LineageQueryService queryService;
    @Autowired
    private MetaTableKeyResolver resolver;

    private JdbcTemplate jdbc;

    static boolean postgresAvailable() {
        if (Boolean.getBoolean("standard.postgres.external")) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @BeforeEach
    void installLineageSchema() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        String store = migration("0273_governance_lineage-store.sql");
        jdbc.execute(store);
        jdbc.execute(store);
        createFromEntity(MetaDbDO.class);
        createFromEntity(MetaTableDO.class);
        createFromEntity(MetaColumnDO.class);
    }

    // ========== 迁移 ==========

    @Test
    void storeMigrationIsRepeatableAndEnforcesShapes() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                + "'datahub-center' AND table_name LIKE 'dh_governance_lineage_%'", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = 'datahub-center' "
                + "AND indexname LIKE 'idx_dh_governance_lineage_%'", Long.class)).isEqualTo(6L);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dh_governance_lineage_node(node_key, node_type, "
                + "datasource_id, external_kind, external_key) VALUES ('k', 'EXTERNAL', 1, 'KAFKA', 't')"))
                .hasMessageContaining("ck_lineage_node_shape");
        jdbc.update("INSERT INTO dh_governance_lineage_source(id, source_type, source_id, status) "
                + "VALUES (900, 'DEV_TASK', 900, 'SUCCESS')");
        jdbc.update("INSERT INTO dh_governance_lineage_node(id, node_key, node_type, datasource_id, table_name) "
                + "VALUES (900, 'a', 'TABLE', 1, 'a')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dh_governance_lineage_column_edge(source_ref_id, "
                + "upstream_node_id, upstream_column, downstream_node_id, downstream_column, transform_type, "
                + "transform_subtype) VALUES (900, 900, 'c', 900, 'c', 'DIRECT', 'JOIN')"))
                .hasMessageContaining("ck_lineage_column_edge_transform");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dh_governance_lineage_table_edge(source_ref_id, "
                + "upstream_node_id, downstream_node_id, origin) VALUES (900, 901, 900, 'PARSED')"))
                .hasMessageContaining("fk_lineage_table_edge_upstream");
    }

    @Test
    void menuMigrationCreatesPageButtonAndGrantOnceAndRejectsConflicts() throws Exception {
        createMenuTables();
        jdbc.update("INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path) VALUES "
                + "(5993, '数据治理', '', 1, 4, 0, '/govern'), (5988, '元数据管理', '', 1, 1, 5993, 'metadata')");
        jdbc.update("INSERT INTO system_role(id, code, status, tenant_id) VALUES (1, 'super_admin', 0, 1), "
                + "(2, 'common', 0, 1)");
        String menu = migration("0274_governance_lineage-menu.sql");
        jdbc.execute(menu);
        jdbc.execute(menu);

        Long pageId = jdbc.queryForObject("SELECT id FROM system_menu WHERE component = 'governance/lineage/index' "
                + "AND parent_id = 5988 AND path = 'lineage' AND type = 2 AND component_name = 'GovernanceLineage' "
                + "AND icon = 'carbon:flow-data'", Long.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE permission = 'governance:lineage:query' "
                + "AND type = 3 AND parent_id = ?", Long.class, pageId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_menu WHERE role_id = 1", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_menu WHERE role_id = 2", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_value FROM system_menu_seq", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT max(id) FROM system_menu", Long.class));

        jdbc.update("DELETE FROM system_role_menu");
        jdbc.update("DELETE FROM system_menu WHERE id > 5993");
        jdbc.update("INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, component) "
                + "VALUES (7000, '别的页面', '', 2, 9, 5988, 'lineage', 'other/index')");
        assertThatThrownBy(() -> jdbc.execute(menu)).hasMessageContaining("0274 菜单路径冲突");
    }

    // ========== 上报 ==========

    @Test
    void reportLifecycleReplacesPhysicallyAndHonoursRevisions() {
        assertThat(reportService.report(simpleReport(10, 1, HASH_A)).getResult()).isEqualTo("ACCEPTED");
        assertThat(count("dh_governance_lineage_table_edge")).isEqualTo(1L);
        assertThat(count("dh_governance_lineage_column_edge")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(issues) FROM dh_governance_lineage_source "
                + "WHERE source_id = 10", Integer.class)).isEqualTo(1);

        assertThat(reportService.report(simpleReport(10, 1, HASH_A)).getResult()).isEqualTo("NOOP");

        LineageReportReqDTO v2 = report(10, 2, 1, HASH_B);
        v2.getTableEdges().add(tableEdge(table(1, "dwd", "orders"), table(1, "ads", "sales")));
        v2.getColumnEdges().add(columnEdge(table(1, "dwd", "orders"), "amount_yuan", table(1, "ads", "sales"),
                "amount", "DIRECT", "AGGREGATION"));
        assertThat(reportService.report(v2).getResult()).isEqualTo("ACCEPTED");
        assertThat(count("dh_governance_lineage_column_edge")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT downstream_column FROM dh_governance_lineage_column_edge",
                String.class)).isEqualTo("amount");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dh_governance_lineage_table_edge WHERE deleted <> 0",
                Long.class)).isZero();

        LineageReportRespDTO stale = reportService.report(simpleReport(10, 1, HASH_B));
        assertThat(stale.getResult()).isEqualTo("STALE");
        assertThat(stale.getStoredRevision()).isEqualTo(2L);

        assertThat(reportService.report(removed(10, 2, 1)).getResult()).isEqualTo("ACCEPTED");
        assertThat(count("dh_governance_lineage_table_edge")).isZero();
        assertThat(count("dh_governance_lineage_column_edge")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM dh_governance_lineage_source WHERE source_id = 10",
                String.class)).isEqualTo("REMOVED");
        assertThat(reportService.report(removed(10, 2, 1)).getResult()).isEqualTo("NOOP");
        assertThat(reportService.report(v2).getResult()).isEqualTo("STALE");

        v2.setSourceRevision(3L);
        assertThat(reportService.report(v2).getResult()).isEqualTo("ACCEPTED");
        assertThat(count("dh_governance_lineage_table_edge")).isEqualTo(1L);
        assertThat(count("dh_governance_lineage_source")).isEqualTo(1L);
    }

    @Test
    void nodesAreSharedThroughOnConflictAndDisplayNamesOnlyFilled() {
        reportService.report(simpleReport(10, 1, HASH_A));
        LineageReportReqDTO other = report(11, 1, 1, HASH_A);
        LineageNodeRefDTO ods = table(1, "ODS", "Orders");
        ods.setDisplayName("订单");
        other.getTableEdges().add(tableEdge(ods, table(1, "dwd", "orders")));
        reportService.report(other);
        LineageReportReqDTO renamed = report(12, 1, 1, HASH_A);
        LineageNodeRefDTO again = table(1, "ods", "orders");
        again.setDisplayName("别名");
        renamed.getTableEdges().add(tableEdge(again, table(1, "dwd", "orders")));
        reportService.report(renamed);

        assertThat(count("dh_governance_lineage_node")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT display_name FROM dh_governance_lineage_node "
                + "WHERE node_key = 'TABLE|1|ods|orders'", String.class)).isEqualTo("订单");
        assertThat(jdbc.queryForObject("SELECT schema_name FROM dh_governance_lineage_node "
                + "WHERE node_key = 'TABLE|1|ods|orders'", String.class)).isEqualTo("ods");
        assertThat(count("dh_governance_lineage_table_edge")).isEqualTo(3L);
    }

    @Test
    void reportsOfTheSameSourceWaitForTheSourceRowLock() throws Exception {
        reportService.report(simpleReport(10, 1, HASH_A));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("SELECT id FROM dh_governance_lineage_source WHERE source_type = 'DEV_TASK' "
                        + "AND source_id = 10 FOR UPDATE");
            }
            Future<LineageReportRespDTO> pending = executor.submit(() -> reportService.report(simpleReport(10, 2, HASH_B)));
            assertThatThrownBy(() -> pending.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            lock.commit();
            assertThat(pending.get(20, TimeUnit.SECONDS).getResult()).isEqualTo("ACCEPTED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT source_revision FROM dh_governance_lineage_source WHERE source_id = 10",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void concurrentOutOfOrderReportsKeepOnlyTheHighestRevision() throws Exception {
        List<Integer> revisions = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        Collections.shuffle(revisions);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<LineageReportRespDTO>> futures = new ArrayList<>();
            for (int revision : revisions) {
                futures.add(executor.submit(() -> {
                    LineageReportReqDTO req = report(20, revision, 1, String.format("%064x", revision));
                    req.getTableEdges().add(tableEdge(table(1, "ods", "src"), table(1, "dw", "rev_" + revision)));
                    return reportService.report(req);
                }));
            }
            for (Future<LineageReportRespDTO> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).getResult()).isIn("ACCEPTED", "STALE");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT source_revision FROM dh_governance_lineage_source WHERE source_id = 20",
                Long.class)).isEqualTo(8L);
        assertThat(jdbc.queryForList("SELECT n.table_name FROM dh_governance_lineage_table_edge e "
                + "JOIN dh_governance_lineage_node n ON n.id = e.downstream_node_id", String.class))
                .containsExactly("rev_8");
    }

    @Test
    void concurrentFirstReportsSharingNewNodesDoNotDeadlock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<LineageReportRespDTO>> futures = new ArrayList<>();
            for (int source = 0; source < 6; source++) {
                int sourceId = 100 + source;
                boolean reverse = source % 2 == 1;
                futures.add(executor.submit(() -> {
                    LineageReportReqDTO req = report(sourceId, 1, 1, HASH_A);
                    for (int i = 0; i < 50; i++) {
                        int n = reverse ? 49 - i : i;
                        req.getTableEdges().add(tableEdge(table(3, "s", "t" + n), table(3, "s", "t" + (n + 1))));
                    }
                    return reportService.report(req);
                }));
            }
            for (Future<LineageReportRespDTO> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).getResult()).isEqualTo("ACCEPTED");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("dh_governance_lineage_node")).isEqualTo(51L);
        assertThat(count("dh_governance_lineage_table_edge")).isEqualTo(300L);
    }

    // ========== 查询 ==========

    @Test
    void queriesUseRealRowValueSqlAndMetadataMatching() {
        seedGraph();
        insertMeta(1L, "dwd", 501L, "ORDERS", "明细订单", List.of("ID", "Amount_Yuan", "remark"));

        List<LineageNodeRespVO> found = queryService.searchNodes(search("orders"));
        assertThat(found).extracting(LineageNodeRespVO::getTableName).contains("orders");
        assertThat(queryService.searchNodes(search("%"))).isEmpty();
        assertThat(queryService.searchNodes(search("明细"))).singleElement()
                .satisfies(node -> assertThat(node.getSchemaName()).isEqualTo("dwd"));

        LineageNodeRespVO dwd = queryService.resolveByMetaTable(501L);
        assertThat(dwd).isNotNull();
        assertThat(dwd.getMetaTableId()).isEqualTo(501L);
        assertThat(dwd.getBusinessName()).isEqualTo("明细订单");

        LineageGraphReqVO graph = new LineageGraphReqVO();
        graph.setNodeId(dwd.getId());
        graph.setDirection("BOTH");
        graph.setDepth(2);
        var result = queryService.getGraph(graph);
        assertThat(result.getNodes()).extracting(n -> n.getDisplayName() == null ? n.getTableName() : n.getDisplayName())
                .hasSize(4);
        assertThat(result.getNodes()).filteredOn(n -> "EXTERNAL".equals(n.getNodeType())).singleElement()
                .satisfies(n -> assertThat(n.getDepth()).isEqualTo(-2));
        assertThat(result.getEdges()).hasSize(3).allSatisfy(edge -> assertThat(edge.getSources()).hasSize(1));
        assertThat(result.getTruncated()).isFalse();

        LineageColumnGraphReqVO columnGraph = new LineageColumnGraphReqVO();
        columnGraph.setNodeId(nodeId("TABLE|1|ads|sales"));
        columnGraph.setColumn("AMOUNT");
        columnGraph.setDirection("UPSTREAM");
        columnGraph.setDepth(3);
        var columns = queryService.getColumnGraph(columnGraph);
        assertThat(columns.getNodes()).extracting(n -> n.getColumn() + "@" + n.getDepth())
                .contains("amount@0", "amount_yuan@-1", "amount@-2", "status@-1");
        assertThat(columns.getEdges()).filteredOn(e -> "FILTER".equals(e.getTransformSubtype())).singleElement()
                .satisfies(e -> assertThat(e.getTransformType()).isEqualTo("INDIRECT"));

        LineageImpactReqVO impact = new LineageImpactReqVO();
        impact.setNodeId(nodeId("TABLE|1|ods|orders"));
        assertThat(queryService.getImpact(impact).getItems()).extracting(i -> i.getNode().getTableName() + "@" + i.getDepth())
                .containsExactly("orders@1", "sales@2");
        impact.setColumn("Amount");
        assertThat(queryService.getImpact(impact).getItems()).extracting(i -> i.getColumn() + "@" + i.getDepth())
                .containsExactly("amount_yuan@1", "amount@2");

        assertThat(queryService.getNodeColumns(dwd.getId())).containsExactly("ID", "Amount_Yuan", "remark", "status");

        LineageSourcePageReqVO page = new LineageSourcePageReqVO();
        page.setKeyword("task-2");
        var sources = queryService.getSourcePage(page);
        assertThat(sources.getTotal()).isEqualTo(1L);
        assertThat(sources.getList().get(0).getIssues()).singleElement()
                .satisfies(issue -> assertThat(issue.getCode()).isEqualTo("UNSUPPORTED_STATEMENT"));
        page.setKeyword("3");
        assertThat(queryService.getSourcePage(page).getList()).extracting(s -> s.getSourceId()).containsExactly(3L);
    }

    @Test
    void resolverUsesExactThenUniqueCaseInsensitiveSqlAndSkipsDeleted() {
        insertMeta(1L, "ods", 601L, "Orders", "订单", List.of());
        insertMeta(1L, "ods", 602L, "orders", "订单小写", List.of());
        insertMeta(1L, "dwd", 603L, "Customer", "客户", List.of("id"));
        insertMeta(1L, "ads", 604L, "gone", "已删除", List.of());
        jdbc.update("UPDATE dh_governance_meta_table SET object_status = 3 WHERE id = 604");

        assertThat(match(1L, "ods", "orders")).isEqualTo(602L);
        assertThat(match(1L, "ODS", "ORDERS")).isNull();
        assertThat(match(1L, "DWD", "customer")).isEqualTo(603L);
        assertThat(match(1L, null, "CUSTOMER")).isEqualTo(603L);
        assertThat(match(1L, "ads", "gone")).isNull();
        assertThat(resolver.loadColumnNames(List.of(603L)).get(603L)).containsExactly("id");
    }

    // ========== 夹具 ==========

    private void seedGraph() {
        LineageReportReqDTO s1 = report(1, 1, 1, HASH_A);
        var topic = LineageNodeRefDTO.external("KAFKA", "orders.events");
        s1.getTableEdges().add(tableEdge(topic, table(1, "ods", "orders")));
        reportService.report(s1);
        reportService.report(simpleReport(2, 1, HASH_A));
        LineageReportReqDTO s3 = report(3, 1, 1, HASH_A);
        var dwd = table(1, "dwd", "orders");
        var ads = table(1, "ads", "sales");
        s3.getTableEdges().add(tableEdge(dwd, ads));
        s3.getColumnEdges().add(columnEdge(dwd, "amount_yuan", ads, "amount", "DIRECT", "AGGREGATION"));
        s3.getColumnEdges().add(columnEdge(dwd, "status", ads, "amount", "INDIRECT", "FILTER"));
        reportService.report(s3);
    }

    private void insertMeta(Long datasourceId, String schema, Long tableId, String tableName, String businessName,
            List<String> columns) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM dh_governance_meta_db WHERE datasource_id = ? "
                + "AND schema_name = ?", Long.class, datasourceId, schema);
        Long dbId = existing.isEmpty() ? null : existing.get(0);
        if (dbId == null) {
            dbId = tableId * 10;
            jdbc.update("INSERT INTO dh_governance_meta_db(id, datasource_id, db_name, schema_name, object_status, deleted) "
                    + "VALUES (?, ?, ?, ?, 1, 0)", dbId, datasourceId, schema, schema);
        }
        jdbc.update("INSERT INTO dh_governance_meta_table(id, db_id, datasource_id, table_name, business_name, "
                + "object_status, deleted) VALUES (?, ?, ?, ?, ?, 1, 0)", tableId, dbId, datasourceId, tableName,
                businessName);
        for (int i = 0; i < columns.size(); i++) {
            jdbc.update("INSERT INTO dh_governance_meta_column(id, table_id, db_id, column_name, ordinal_position, "
                    + "object_status, deleted) VALUES (?, ?, ?, ?, ?, 1, 0)", tableId * 100 + i, tableId, dbId,
                    columns.get(i), i + 1);
        }
    }

    private Long match(Long datasourceId, String schema, String table) {
        TableKey key = new TableKey(datasourceId, schema, table);
        var hit = resolver.resolve(List.of(key)).get(key);
        return hit == null ? null : hit.tableId();
    }

    private Long nodeId(String nodeKey) {
        return jdbc.queryForObject("SELECT id FROM dh_governance_lineage_node WHERE node_key = ?", Long.class, nodeKey);
    }

    private static LineageNodeSearchReqVO search(String keyword) {
        LineageNodeSearchReqVO req = new LineageNodeSearchReqVO();
        req.setKeyword(keyword);
        return req;
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private void createFromEntity(Class<?> entity) {
        var info = TableInfoHelper.getTableInfo(entity);
        List<String> columns = new ArrayList<>();
        columns.add(info.getKeyColumn() + " bigint PRIMARY KEY");
        for (TableFieldInfo field : info.getFieldList()) {
            Class<?> type = field.getPropertyType();
            String sqlType = field.getTypeHandler() == JsonbTypeHandler.class ? "jsonb"
                    : type == Long.class ? "bigint"
                    : type == Integer.class ? "integer"
                    : type == LocalDateTime.class ? "timestamp"
                    : type == BigDecimal.class ? "numeric"
                    : type == Boolean.class ? "smallint DEFAULT 0"
                    : "text";
            columns.add(field.getColumn() + " " + sqlType);
        }
        jdbc.execute("CREATE TABLE " + info.getTableName() + " (" + String.join(", ", columns) + ")");
    }

    private void createMenuTables() {
        jdbc.execute("""
                CREATE SEQUENCE system_menu_seq;
                CREATE SEQUENCE system_menu_id_seq;
                CREATE SEQUENCE system_role_menu_seq;
                CREATE TABLE system_menu (id bigint PRIMARY KEY, name varchar(50) NOT NULL, permission varchar(100) DEFAULT '',
                  type smallint NOT NULL, sort integer DEFAULT 0, parent_id bigint DEFAULT 0, path varchar(200) DEFAULT '',
                  icon varchar(100) DEFAULT '#', component varchar(255), component_name varchar(255),
                  status smallint DEFAULT 0, visible bool DEFAULT true, keep_alive bool DEFAULT true,
                  always_show bool DEFAULT true, creator varchar(64) DEFAULT '', create_time timestamp DEFAULT now(),
                  updater varchar(64) DEFAULT '', update_time timestamp DEFAULT now(), deleted smallint DEFAULT 0);
                CREATE TABLE system_role (id bigint PRIMARY KEY, code varchar(100), status smallint DEFAULT 0,
                  tenant_id bigint DEFAULT 1, deleted smallint DEFAULT 0);
                CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint, tenant_id bigint,
                  creator varchar(64) DEFAULT '', create_time timestamp DEFAULT now(), updater varchar(64) DEFAULT '',
                  update_time timestamp DEFAULT now(), deleted smallint DEFAULT 0);
                """);
    }

    private static String migration(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/migrations"))) {
            root = root.getParent();
        }
        return Files.readString(root.resolve("sql/postgresql/migrations/" + name));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = LineageSourceMapper.class, sqlSessionFactoryRef = "sqlSessionFactory")
    @Import({LineageReportServiceImpl.class, LineageQueryServiceImpl.class, MetaTableKeyResolver.class})
    static class LineageConfig {

        @Bean
        MapperFactoryBean<MetaDbMapper> metaDbMapper(SqlSessionFactory sqlSessionFactory) {
            return mapper(MetaDbMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<MetaTableMapper> metaTableMapper(SqlSessionFactory sqlSessionFactory) {
            return mapper(MetaTableMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<MetaColumnMapper> metaColumnMapper(SqlSessionFactory sqlSessionFactory) {
            return mapper(MetaColumnMapper.class, sqlSessionFactory);
        }

        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionFactory(sqlSessionFactory);
            return bean;
        }
    }

}
