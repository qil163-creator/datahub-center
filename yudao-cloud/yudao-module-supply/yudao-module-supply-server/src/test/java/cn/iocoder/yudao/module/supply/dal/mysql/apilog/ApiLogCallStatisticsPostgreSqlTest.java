package cn.iocoder.yudao.module.supply.dal.mysql.apilog;

import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 上验证开放网关调用统计只计 APP_GATEWAY 真实调用：排除预览、管理端执行、测试运行、
 * 已删除记录与窗口外记录；未提供 -Ddatahub.test.postgres.url 时跳过。
 */
class ApiLogCallStatisticsPostgreSqlTest {

    private final String schema = "supply_call_stat_" + UUID.randomUUID().toString().replace("-", "");
    private Connection control;
    private ApiLogMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        Assumptions.assumeTrue(url != null && url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+"),
                "未配置本地测试 PostgreSQL");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        var create = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"dh_supply_api_log\" \\(.*?\n\\)\\s*;", Pattern.DOTALL)
                .matcher(baseline);
        assertThat(create.find()).isTrue();
        try (var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE SEQUENCE " + schema + ".dh_supply_api_log_seq");
            sql.execute(create.group().replace("\"datahub-center\"", schema));
        }
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("supply-call-stat", new SpringManagedTransactionFactory(), source));
        config.addMapper(ApiLogMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(ApiLogMapper.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (control != null) {
            try (var sql = control.createStatement()) {
                assertThat(schema).matches("supply_call_stat_[a-f0-9]{32}");
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } finally {
                control.close();
            }
        }
    }

    @Test
    void countsOnlyRealGatewayCallsInsideTheWindow() throws Exception {
        try (var sql = control.createStatement()) {
            sql.execute("INSERT INTO " + schema + ".dh_supply_api_log(api_id,app_id,call_time,status,call_mode,test_run_no,deleted) VALUES"
                    + " (900,1,'2026-11-08 10:00',1,'APP_GATEWAY',NULL,0),"
                    + " (900,2,'2026-11-09 11:00',0,'APP_GATEWAY',NULL,0),"
                    + " (900,1,'2026-11-09 12:00',1,'APP_GATEWAY',NULL,0),"
                    + " (900,3,'2026-11-09 13:00',1,'APP_GATEWAY','T-1',0),"       // 测试运行
                    + " (900,4,'2026-11-09 14:00',1,'ADMIN_EXECUTE',NULL,0),"      // 管理端执行
                    + " (900,5,'2026-11-09 15:00',1,'DRAFT_READONLY_PREVIEW',NULL,0),"
                    + " (900,6,'2026-11-09 16:00',1,'APP_GATEWAY',NULL,1),"        // 已删除
                    + " (900,7,'2026-09-01 10:00',1,'APP_GATEWAY',NULL,0),"        // 窗口外
                    + " (901,8,'2026-11-01 10:00',1,'APP_GATEWAY',NULL,0),"
                    + " (902,9,'2026-11-01 10:00',1,'APP_GATEWAY',NULL,0)");       // 未请求
        }
        List<SupplyApiCallStatRespDTO> stats = mapper.selectCallStatistics(List.of(900L, 901L, 903L),
                LocalDateTime.of(2026, 10, 11, 0, 0)).stream()
                .sorted(Comparator.comparing(SupplyApiCallStatRespDTO::getApiId)).toList();

        assertThat(stats).hasSize(2);
        SupplyApiCallStatRespDTO main = stats.getFirst();
        assertThat(main.getApiId()).isEqualTo(900L);
        assertThat(main.getCallCount()).isEqualTo(3L);
        assertThat(main.getSuccessCount()).isEqualTo(2L);
        assertThat(main.getAppCount()).isEqualTo(2L);
        assertThat(main.getLastCallTime()).isEqualTo(LocalDateTime.of(2026, 11, 9, 12, 0));
        assertThat(stats.get(1).getApiId()).isEqualTo(901L);
        assertThat(stats.get(1).getCallCount()).isEqualTo(1L);
    }

}
