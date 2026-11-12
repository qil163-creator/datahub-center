package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskVersionMapper;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevTaskLineageCollectorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DevTaskLineageCollector collector;
    @Mock
    private DevTaskMapper taskMapper;
    @Mock
    private DevTaskVersionMapper versionMapper;
    @Mock
    private LineageDatasourceResolver datasources;
    @Mock
    private LineageCatalogProvider catalogs;

    @Test
    void sqlTaskReportsParsedAndManualLineageWithCatalogExpandedWildcard() {
        when(datasources.resolve(5L)).thenReturn(new LineageDatasourceResolver.Datasource(5L,
                ProductTypeEnum.POSTGRESQL, "public"));
        when(catalogs.prefetch(eq(5L), anyCollection())).thenReturn(new LineageCatalogProvider.Prefetched(
                table -> "ods".equals(table.schema()) && "orders".equals(table.name())
                        ? Optional.of(List.of("id", "amount")) : Optional.empty(), false));
        DevTaskDO task = task("SQL", 5L, "INSERT INTO dw.fact (id, amount) SELECT * FROM ods.orders",
                manualLineage(new LineageTable("ods", "users"), new LineageTable("dw", "fact")));

        var analysis = collector.analyze(task, 42L);
        LineageReportReqDTO report = analysis.report();
        assertEquals("SUCCESS", report.getStatus(), report.getIssues().toString());
        assertEquals(42L, report.getSourceRevision());
        assertEquals(LineageReportVersions.DEV_TASK, report.getAnalyzerVersion());
        assertEquals(Set.of("PARSED 5:ods.orders -> 5:dw.fact", "MANUAL 5:ods.users -> 5:dw.fact"), tables(report));
        assertEquals(Set.of("ods.orders.id -> dw.fact.id IDENTITY", "ods.orders.amount -> dw.fact.amount IDENTITY"),
                columns(report));
        assertEquals(List.of(new LineageTable("ods", "orders")), analysis.readTables());
    }

    @Test
    void localParametersAreSubstitutedAndUnqualifiedTablesUseTheDatasourceSchema() {
        when(datasources.resolve(5L)).thenReturn(new LineageDatasourceResolver.Datasource(5L,
                ProductTypeEnum.POSTGRESQL, "stage"));
        when(catalogs.prefetch(eq(5L), anyCollection())).thenReturn(
                new LineageCatalogProvider.Prefetched(table -> Optional.empty(), false));
        DevExecutionConfig config = new DevExecutionConfig();
        DevExecutionConfig.LocalParam param = new DevExecutionConfig.LocalParam();
        param.setProp("target");
        param.setValue("dw.daily");
        config.setLocalParams(List.of(param));
        DevTaskDO task = task("SQL", 5L, "INSERT INTO ${target} (id) SELECT id FROM src", config);
        assertEquals(Set.of("PARSED 5:stage.src -> 5:dw.daily"), tables(collector.analyze(task, 1L).report()));
    }

    @Test
    void sparkSqlUsesHiveDialectEvenWhenBoundDatasourceIsAnotherProduct() {
        when(datasources.resolve(9L)).thenReturn(new LineageDatasourceResolver.Datasource(9L,
                ProductTypeEnum.MYSQL, "ods"));
        when(catalogs.prefetch(eq(9L), anyCollection())).thenReturn(
                new LineageCatalogProvider.Prefetched(table -> Optional.empty(), false));
        DevTaskDO task = task("SPARK_SQL", 9L,
                "INSERT OVERWRITE TABLE dw.t PARTITION (dt = '2024') SELECT id FROM ods.s", new DevExecutionConfig());
        LineageReportReqDTO report = collector.analyze(task, 1L).report();
        assertEquals(Set.of("PARSED 9:ods.s -> 9:dw.t"), tables(report));
    }

    @Test
    void unsupportedTaskTypesKeepManualLineageOnly() {
        DevTaskDO task = task("FLINK_SQL", 5L, "INSERT INTO sink SELECT * FROM src",
                manualLineage(new LineageTable("ods", "a"), new LineageTable("dw", "b")));
        LineageReportReqDTO report = collector.analyze(task, 1L).report();
        assertEquals("UNSUPPORTED", report.getStatus());
        assertEquals(Set.of("MANUAL 5:ods.a -> 5:dw.b"), tables(report));
        verify(datasources, never()).resolve(5L);
    }

    @Test
    void missingMetadataServiceDegradesButKeepsTableLineage() {
        when(datasources.resolve(5L)).thenReturn(new LineageDatasourceResolver.Datasource(5L,
                ProductTypeEnum.POSTGRESQL, "public"));
        when(catalogs.prefetch(eq(5L), anyCollection())).thenReturn(
                new LineageCatalogProvider.Prefetched(table -> Optional.empty(), true));
        LineageReportReqDTO report = collector.analyze(task("SQL", 5L,
                "INSERT INTO dw.t (a) SELECT a FROM ods.s", new DevExecutionConfig()), 1L).report();
        assertEquals("PARTIAL", report.getStatus());
        assertTrue(report.getIssues().stream().anyMatch(issue -> "METADATA_UNAVAILABLE".equals(issue.getCode())));
        assertEquals(Set.of("PARSED 5:ods.s -> 5:dw.t"), tables(report));
    }

    @Test
    void removedTasksReportRemovedWithoutReadingVersions() {
        LineageMarkerRow row = new LineageMarkerRow();
        row.setId(3L);
        row.setSourceName("old");
        row.setSourceRevision(8L);
        row.setRemoved(true);
        LineageReportReqDTO report = collector.build(row);
        assertEquals("REMOVED", report.getStatus());
        verify(versionMapper, never()).selectById(8L);
    }

    // ==================== 工具 ====================

    private static DevExecutionConfig manualLineage(LineageTable upstream, LineageTable downstream) {
        DevExecutionConfig config = new DevExecutionConfig();
        DevExecutionConfig.TableLineage lineage = new DevExecutionConfig.TableLineage();
        lineage.setUpstream(List.of(reference(upstream)));
        lineage.setDownstream(List.of(reference(downstream)));
        config.setTableLineage(lineage);
        return config;
    }

    private static DevExecutionConfig.TableReference reference(LineageTable table) {
        DevExecutionConfig.TableReference reference = new DevExecutionConfig.TableReference();
        reference.setDatabaseId(5L);
        reference.setSchemaName(table.schema());
        reference.setTableName(table.name());
        return reference;
    }

    private static DevTaskDO task(String type, Long databaseId, String content, DevExecutionConfig config) {
        DevTaskDO task = new DevTaskDO();
        task.setId(7L);
        task.setName("ods_to_dw");
        task.setTaskType(type);
        task.setDatabaseId(databaseId);
        task.setContent(content);
        task.setExecutionConfigJson(JsonUtils.toJsonString(config));
        return task;
    }

    private static Set<String> tables(LineageReportReqDTO report) {
        Set<String> edges = new TreeSet<>();
        report.getTableEdges().forEach(edge -> edges.add(edge.getOrigin() + " " + edge.getUpstream().getDatasourceId()
                + ":" + edge.getUpstream().getSchemaName() + "." + edge.getUpstream().getTableName() + " -> "
                + edge.getDownstream().getDatasourceId() + ":" + edge.getDownstream().getSchemaName() + "."
                + edge.getDownstream().getTableName()));
        return edges;
    }

    private static Set<String> columns(LineageReportReqDTO report) {
        Set<String> edges = new TreeSet<>();
        report.getColumnEdges().forEach(edge -> edges.add(edge.getUpstream().getSchemaName() + "."
                + edge.getUpstream().getTableName() + "." + edge.getUpstreamColumn() + " -> "
                + edge.getDownstream().getSchemaName() + "." + edge.getDownstream().getTableName() + "."
                + edge.getDownstreamColumn() + " " + edge.getTransformSubtype()));
        return edges;
    }
}
