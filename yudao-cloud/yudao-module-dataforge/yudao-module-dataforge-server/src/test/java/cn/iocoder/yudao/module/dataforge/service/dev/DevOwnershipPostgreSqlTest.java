package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlExecutionPlanService;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class DevOwnershipPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void activeUniquenessAndFencedUpdatesUseRealPostgresql() throws Exception {
        var source = new PooledDataSource("org.postgresql.Driver", POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS dh_dataforge_dev_record (id bigint primary key, task_id bigint, tenant_id bigint,"
                    + "status varchar(32), version_id bigint, lease_owner varchar(64), snapshot_json jsonb, deleted smallint default 0)");
            sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS dev_one_active ON dh_dataforge_dev_record(tenant_id,task_id)"
                    + " WHERE deleted=0 AND version_id IS NOT NULL AND status IN('SUBMITTING','RUNNING','UNKNOWN','CANCEL_REQUESTED')");
            sql.execute("TRUNCATE dh_dataforge_dev_record");
            sql.execute("INSERT INTO dh_dataforge_dev_record(id,task_id,status,version_id,lease_owner) VALUES(1,10,'RUNNING',100,'owner'),(2,10,'WAITING',100,null),(3,10,'RUNNING',100,'other-tenant')");
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("dev-pg", new JdbcTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
            config.addMapper(DevRecordMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(DevRecordMapper.class);
                assertEquals(0, mapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate()
                        .set(DevRecordDO::getStatus, "SUCCESS").eq(DevRecordDO::getId, 1L).eq(DevRecordDO::getLeaseOwner, "stale")));
                assertThrows(Exception.class, () -> mapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate()
                        .set(DevRecordDO::getStatus, "SUBMITTING").eq(DevRecordDO::getId, 2L)));
                assertEquals(1, mapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate()
                        .set(DevRecordDO::getStatus, "SUCCESS").eq(DevRecordDO::getId, 1L).eq(DevRecordDO::getLeaseOwner, "owner")));
                assertEquals(1, mapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate()
                        .set(DevRecordDO::getStatus, "SUBMITTING").eq(DevRecordDO::getId, 2L)));
            }
        } finally { source.forceCloseAll(); }
    }

    @Test void explainDoesNotExecuteTheSelect() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("DROP SEQUENCE IF EXISTS dev_plan_probe_seq");
            sql.execute("CREATE SEQUENCE dev_plan_probe_seq");
            var plan = new SqlExecutionPlanService(() -> DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())).explain(ProductTypeEnum.POSTGRESQL,
                    "SELECT nextval('dev_plan_probe_seq'), ';' AS separator", 20, 10);
            assertFalse(plan.getRows().isEmpty());
            try (var rs = sql.executeQuery("SELECT is_called FROM dev_plan_probe_seq")) {
                assertTrue(rs.next()); assertFalse(rs.getBoolean(1));
            }
        }
    }
}
