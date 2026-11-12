package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.support.StandardPostgreSqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL：0277 迁移重放与按钮授权、部分唯一索引单表并发限制、CAS 领取/续租/失联恢复/次数用尽，
 * 以及结果落库与“未脱敏必须有文本”约束。
 *
 * <p>正常路径用 Testcontainers；无 Docker 且未显式指定外部测试库时整类跳过（跳过不等于通过）。</p>
 */
@EnabledIf("postgresAvailable")
@ContextConfiguration(classes = ProfileRunPostgreSqlTest.ProfileConfig.class)
@Timeout(120)
class ProfileRunPostgreSqlTest extends StandardPostgreSqlIntegrationTestBase {

    private static final String MIGRATION = "0277_governance_data-profile.sql";

    @Autowired
    private ProfileRunMapper runMapper;
    @Autowired
    private ProfileResultWriter writer;

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
    void installSchema() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        createMenuTables();
        jdbc.update("INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, component) VALUES "
                + "(5988, '元数据管理', '', 1, 1, 0, 'metadata', NULL), "
                + "(6231, '最新元数据', 'governance:meta-object:query', 2, 3, 5988, 'metaobject', "
                + "'governance/metaobject/index')");
        jdbc.update("INSERT INTO system_role(id, code, status, tenant_id) VALUES (1, 'super_admin', 0, 1), "
                + "(2, 'common', 0, 1)");
        String sql = migration();
        jdbc.execute(sql);
        jdbc.execute(sql);
    }

    @Test
    void migrationIsRepeatableAndGrantsButtonsOnlyToSuperAdmin() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                + "'datahub-center' AND table_name LIKE 'dh_governance_profile_%'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE parent_id = 6231 AND type = 3 "
                + "AND permission IN ('governance:profile:run', 'governance:profile:query')", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_menu WHERE role_id = 1", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_menu WHERE role_id = 2", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT col_description('dh_governance_profile_run'::regclass, 1)",
                String.class)).isEqualTo("探查记录编号");
    }

    @Test
    void partialUniqueIndexAllowsOnlyOneActiveRunPerTable() {
        ProfileRunDO first = pending(100L);
        runMapper.insert(first);
        assertThatThrownBy(() -> runMapper.insert(pending(100L))).isInstanceOf(DuplicateKeyException.class);
        runMapper.insert(pending(200L));
        jdbc.update("UPDATE dh_governance_profile_run SET status = 'SUCCESS' WHERE id = ?", first.getId());
        runMapper.insert(pending(100L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dh_governance_profile_run WHERE meta_table_id = 100",
                Long.class)).isEqualTo(2L);
    }

    @Test
    void claimRenewRecoverAndExhaustAreCompareAndSet() {
        ProfileRunDO run = pending(300L);
        runMapper.insert(run);
        LocalDateTime now = LocalDateTime.now();
        int max = ProfileRunExecutor.MAX_ATTEMPTS;

        assertThat(runMapper.claim(run.getId(), "a", now, now.plusMinutes(3), max)).isEqualTo(1);
        assertThat(runMapper.claim(run.getId(), "b", now, now.plusMinutes(3), max)).isZero();
        assertThat(runMapper.renew(run.getId(), "b", now.plusMinutes(5))).isZero();
        assertThat(runMapper.renew(run.getId(), "a", now.plusMinutes(5))).isEqualTo(1);
        assertThat(runMapper.selectClaimable(now, max, 10)).isEmpty();

        // a 失联：租约过期后 b 接管，a 的续租与结束都失败
        LocalDateTime later = now.plusMinutes(6);
        assertThat(runMapper.selectClaimable(later, max, 10)).extracting(ProfileRunDO::getId).containsExactly(run.getId());
        assertThat(runMapper.claim(run.getId(), "b", later, later.plusMinutes(3), max)).isEqualTo(1);
        assertThat(runMapper.renew(run.getId(), "a", later.plusMinutes(3))).isZero();
        assertThat(writer.fail(run.getId(), "a", "x", later)).isFalse();
        ProfileRunDO stored = runMapper.selectById(run.getId());
        assertThat(stored.getLeaseOwner()).isEqualTo("b");
        assertThat(stored.getAttemptCount()).isEqualTo(2);
        assertThat(stored.getStartTime()).isNotNull();

        // b 也失联，第三次领取后再失联：次数用尽，不再可领取并被标记失败、释放并发名额
        LocalDateTime third = later.plusMinutes(4);
        assertThat(runMapper.claim(run.getId(), "c", third, third.plusMinutes(3), max)).isEqualTo(1);
        LocalDateTime dead = third.plusMinutes(4);
        assertThat(runMapper.selectClaimable(dead, max, 10)).isEmpty();
        assertThat(runMapper.claim(run.getId(), "d", dead, dead.plusMinutes(3), max)).isZero();
        assertThat(runMapper.failExhausted(dead, max, ProfileRunExecutor.EXHAUSTED_REASON)).isEqualTo(1);
        stored = runMapper.selectById(run.getId());
        assertThat(stored.getStatus()).isEqualTo(ProfileRunDO.STATUS_FAILED);
        assertThat(stored.getLeaseOwner()).isNull();
        runMapper.insert(pending(300L));
    }

    @Test
    void writerPersistsAtomicallyOnlyForLeaseHolder() {
        ProfileRunDO run = pending(400L);
        runMapper.insert(run);
        LocalDateTime now = LocalDateTime.now();
        runMapper.claim(run.getId(), "owner", now, now.plusMinutes(3), ProfileRunExecutor.MAX_ATTEMPTS);

        assertThat(writer.complete(run.getId(), "intruder", 10, 10L, now, List.of(result("x", false)))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dh_governance_profile_column", Long.class)).isZero();

        assertThat(writer.complete(run.getId(), "owner", 10, 10L, now,
                List.of(result("phone", true), result("status", false)))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM dh_governance_profile_run WHERE id = ?", String.class,
                run.getId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dh_governance_profile_value WHERE value_masked = 1 "
                + "AND value_text IS NULL", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT null_rate::text FROM dh_governance_profile_column WHERE "
                + "column_name = 'status'", String.class)).isEqualTo("0.100000");
        Long columnId = jdbc.queryForObject("SELECT id FROM dh_governance_profile_column WHERE column_name = 'status'",
                Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dh_governance_profile_value(run_id, profile_column_id, "
                + "rank_no, value_text, value_count, value_masked) VALUES (?, ?, 9, NULL, 1, 0)", run.getId(), columnId))
                .hasMessageContaining("ck_profile_value_masked");
    }

    private static ProfileRunDO pending(Long tableId) {
        ProfileRunDO run = new ProfileRunDO();
        run.setMetaTableId(tableId);
        run.setTableName("t");
        run.setStatus(ProfileRunDO.STATUS_PENDING);
        run.setSampleLimit(100_000);
        run.setColumnCount(1);
        run.setExecutorUserId(1L);
        run.setAttemptCount(0);
        return run;
    }

    private static ProfileResultWriter.ColumnResult result(String name, boolean masked) {
        ProfileColumnDO column = new ProfileColumnDO();
        column.setColumnName(name);
        column.setColumnKind("TEXT");
        column.setNonNullCount(9L);
        column.setNullCount(1L);
        column.setNullRate(new BigDecimal("0.100000"));
        column.setDistinctCount(2L);
        column.setUniqueRate(new BigDecimal("0.222222"));
        column.setValueMasked(masked ? 1 : 0);
        column.setMinValue(masked ? null : "A");
        column.setMaxValue(masked ? null : "B");
        ProfileValueDO value = new ProfileValueDO();
        value.setRankNo(1);
        value.setValueCount(5L);
        value.setValueMasked(masked ? 1 : 0);
        value.setValueText(masked ? null : "A");
        return new ProfileResultWriter.ColumnResult(column, List.of(value));
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

    private static String migration() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/migrations"))) {
            root = root.getParent();
        }
        return Files.readString(root.resolve("sql/postgresql/migrations/" + MIGRATION));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = ProfileRunMapper.class, sqlSessionFactoryRef = "sqlSessionFactory")
    @Import(ProfileResultWriter.class)
    static class ProfileConfig {

    }

}
