package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresConnectionDeadlinePostgreSqlTest {
    @Test void occupiedOwnedPoolReturnsWithinAcquisitionBudgetWithoutAnExtraConnection()throws Exception {
        var target=new TargetDataSourceProperties();target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());target.setPoolSize(1);
        target.setUrl(System.getProperty("datahub.test.postgres.url"));target.setUsername(System.getProperty("datahub.test.postgres.user","dataforge_test"));target.setPassword(System.getProperty("datahub.test.postgres.password",""));
        try(var runtime=DbSwitchRuntime.standalone();var pool=DataSourceUtils.createTargetDataSource(target,null,null,null,runtime.drivers().runtimes(),DbSwitchOptions.defaults(),1000L);
            var occupied=pool.getConnection();var deadline=new PostgresConnectionDeadline(pool,1)) {
            assertEquals(500,pool.getConnectionTimeout());assertEquals(500,pool.getValidationTimeout());
            long started=System.nanoTime();assertThrows(SQLException.class,deadline::open);
            long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertTrue(elapsed>=300&&elapsed<2500,"Borrowing must use Hikari's bounded queue");
            assertEquals(1,pool.getHikariPoolMXBean().getActiveConnections());assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }
    }
}
