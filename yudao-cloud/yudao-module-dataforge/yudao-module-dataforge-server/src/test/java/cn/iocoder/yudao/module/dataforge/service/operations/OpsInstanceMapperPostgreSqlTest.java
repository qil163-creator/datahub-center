package cn.iocoder.yudao.module.dataforge.service.operations;

import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 通过真实 PostgreSQL 和 MyBatis 读取完整迁移后的隔离测试库。 */
class OpsInstanceMapperPostgreSqlTest {
    @Test void queriesBothOriginsWithRealMappingsFiltersAndStatistics() throws Exception {
        String url = System.getenv("OPS_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "需要显式指定已迁移的隔离 PostgreSQL 测试库");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_test(?:\\?.*)?"));
        var source = new PooledDataSource("org.postgresql.Driver", url,
                System.getenv().getOrDefault("OPS_TEST_JDBC_USER", "ops_test"), "");
        try {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("operations-postgresql", new JdbcTransactionFactory(), source));
            config.addMapper(OpsInstanceMapper.class); config.addMapper(IntegrationCategoryMapper.class);
            config.addMapper(DevDirMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(OpsInstanceMapper.class);
                var service = new OpsInstanceService(); ReflectionTestUtils.setField(service, "mapper", mapper);
                ReflectionTestUtils.setField(service, "integrationCategories", session.getMapper(IntegrationCategoryMapper.class));
                ReflectionTestUtils.setField(service, "developmentCategories", session.getMapper(DevDirMapper.class));
                for (String kind : List.of("INTEGRATION", "DEVELOPMENT")) {
                    var req = new PageReq(); req.setKind(kind);
                    long expected = "INTEGRATION".equals(kind) ? 59 : 24;
                    assertEquals(expected, mapper.countInstances(req, null, null));
                    var rows = mapper.selectInstances(req, null, null, 0);
                    assertEquals(10, rows.size());
                    assertEquals("INTEGRATION".equals(kind) ? 1271 : 1239, rows.getFirst().getId());
                    assertTrue(rows.stream().allMatch(row -> "SOURCE".equals(row.getOrigin())));
                    assertNotNull(rows.getFirst().getCreateTime());
                    req.setStatus("RUNNING");
                    assertEquals("INTEGRATION".equals(kind) ? 3 : 2, mapper.countInstances(req, null, null));
                    req.setStatus(null); req.setStartDate(LocalDate.of(2026, 10, 22)); req.setEndDate(req.getStartDate());
                    assertEquals("INTEGRATION".equals(kind) ? 5 : 3, mapper.countInstances(req, null, null));
                    req.setStartDate(null); req.setEndDate(null); req.setName("' OR 1=1 --");
                    assertEquals(0L, mapper.countInstances(req, null, null)); req.setName(null);
                    req.setSortField("id"); req.setSortOrder("asc");
                    var ascending = mapper.selectInstances(req, null, null, 0);
                    assertTrue(ascending.getFirst().getId() < ascending.getLast().getId());
                    req.setSortField(null); req.setSortOrder(null);
                    var day = LocalDateTime.of(2026, 10, 22, 0, 0);
                    var stats = mapper.selectStatistics(kind, day, day.plusDays(1), null, null, null);
                    assertEquals("INTEGRATION".equals(kind) ? 3 : 2, stats.getRunning());
                    assertEquals(0L, stats.getTodayFailed()); assertEquals(0, stats.getTodaySuccessRate());
                    assertEquals("INTEGRATION".equals(kind) ? 5 : 3, stats.getTodayExecuted());
                    stats.setStatisticTime(LocalDateTime.of(2026, 10, 22, 21, 50));
                    assertEquals(34, service.categories(kind).size());
                    var allRequest = new PageReq(); allRequest.setKind(kind); allRequest.setPageSize(100);
                    var allRows = mapper.selectInstances(allRequest, null, null, 0);
                    save(kind.toLowerCase(Locale.ROOT) + "-page.json", Map.of("page", Map.of("list", allRows, "total", expected),
                            "categories", service.categories(kind), "statistics", stats));
                }
                var dev = new PageReq(); dev.setKind("DEVELOPMENT"); dev.setTaskType("REALTIME");
                assertTrue(mapper.countInstances(dev, null, null) > 0);
                var offline = new PageReq(); offline.setKind("DEVELOPMENT"); offline.setTaskType("OFFLINE");
                assertEquals(24L, mapper.countInstances(dev, null, null) + mapper.countInstances(offline, null, null));
                assertTrue(mapper.countInstances(dev, List.of(14L), "platform") > 0);
                assertEquals(0L, mapper.countInstances(dev, List.of(14L), "source"));
                var day = LocalDateTime.of(2026, 10, 22, 0, 0);
                var scopedStats = mapper.selectStatistics("DEVELOPMENT", day, day.plusDays(1), List.of(14L), "platform", "REALTIME");
                assertEquals(1L, scopedStats.getRunning()); assertEquals(1L, scopedStats.getTodayExecuted());
                for (long id : List.of(1271L, 180829090983840L)) {
                    var identity = identity("INTEGRATION", id);
                    var detail = service.get(identity); assertEquals(id, detail.getId());
                    assertTrue(detail.getFlowJson().contains("nodes"));
                    assertTrue(detail.getDescription() == null || detail.getDescription().isBlank());
                    save("get-" + id + ".json", detail);
                }
                for (var identity : List.of(identity("DEVELOPMENT", 865L), identity("INTEGRATION", 180829090983840L))) {
                    save("log-" + identity.getId() + ".json", service.log(identity));
                    var platform = identity(identity.getKind(), identity.getId()); platform.setOrigin("PLATFORM");
                    assertNull(mapper.selectInstance(platform));
                }
                var connection = session.getConnection(); connection.setAutoCommit(false);
                try (var insert = connection.createStatement()) {
                    insert.execute("INSERT INTO dh_dataforge_ops_development_snapshot(id,task_id,category_id,task_name,instance_name,task_type,execution_type,status,start_time) "
                            + "SELECT 9900000001,task_id,category_id,task_name,'并行统计回归',task_type,'START','RUNNING',start_time "
                            + "FROM dh_dataforge_ops_development_snapshot WHERE id=1239");
                    insert.execute("INSERT INTO dh_dataforge_ops_development_snapshot(id,task_id,category_id,task_name,instance_name,task_type,execution_type,status,start_time) "
                            + "SELECT 9900000002,9900000002,category_id,task_name,'等待统计回归',task_type,'START','WAITING',start_time "
                            + "FROM dh_dataforge_ops_development_snapshot WHERE id=1239");
                    // 夹具通过 JDBC 插入，不经过 MyBatis 更新；清理一级缓存后验证真实数据库结果。
                    session.clearCache();
                    var req = new PageReq(); req.setKind("DEVELOPMENT"); req.setStatus("RUNNING");
                    assertEquals(3L, mapper.countInstances(req, null, null));
                    assertEquals(2L, mapper.selectStatistics("DEVELOPMENT", day, day.plusDays(1), null, null, null).getRunning());
                } finally { connection.rollback(); connection.setAutoCommit(true); session.clearCache(); }
            }
        } finally { source.forceCloseAll(); }
    }

    private static IdentityReq identity(String kind, long id) {
        var req = new IdentityReq(); req.setKind(kind); req.setId(id); req.setOrigin("SOURCE"); return req;
    }

    private static void save(String name, Object value) throws Exception {
        String directory = System.getenv("OPS_TEST_OUTPUT_DIR");
        if (directory == null || directory.isBlank()) return;
        var module = new SimpleModule(); module.addSerializer(Long.class, NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper = JsonMapper.builder().addModule(module).build();
        var target = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(target);
        Files.writeString(target.resolve(name), mapper.writeValueAsString(value));
    }
}
