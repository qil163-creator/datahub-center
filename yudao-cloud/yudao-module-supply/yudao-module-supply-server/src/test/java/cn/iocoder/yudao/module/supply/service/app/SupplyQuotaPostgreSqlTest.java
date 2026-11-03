package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyQuotaMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SupplyQuotaPostgreSqlTest extends cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport {
    @Override protected boolean useLegacyFixture() { return false; }
    SupplyQuotaService service; SupplyQuotaMapper mapper; JdbcTemplate jdbc; String migration; String singleTenantMigration;
    @BeforeEach void setup() throws Exception {
        var ds = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl
                + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=datahub-center", jdbcUser, jdbcPassword);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        migration = migrationSql("0099_supply_consumption-quota.sql");
        jdbc.execute(migration);
        singleTenantMigration = migrationSql("0169_datahub_remove-business-tenancy.sql");
        jdbc.execute(singleTenantMigration);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("quota", new SpringManagedTransactionFactory(), ds)); config.addMapper(SupplyQuotaMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(SupplyQuotaMapper.class);
        service = new SupplyQuotaService(); ReflectionTestUtils.setField(service,"mapper",mapper);
        ReflectionTestUtils.setField(service,"transactionManager",new DataSourceTransactionManager(ds));
        }
    Source source(String key, String metric, int limit, String... fields) { return new Source("asset",key,metric == null ? null : new Quota(metric,limit),List.of(fields)); }
    Reserve req(String channel, String key, int rows, List<Source> sources) { return new Reserve(channel,key,7,"a".repeat(64),rows,List.of("name"),sources); }
    Receipt commit(Receipt r, int rows) { return service.settle(new Settle(r.consumerKey(),r.requestKey(),r.token(),rows,false)); }
    long used() { return jdbc.queryForObject("SELECT COALESCE(sum(used_units),0) FROM dh_supply_quota_balance",Long.class); }
    long reserved() { return jdbc.queryForObject("SELECT COALESCE(sum(reserved_units),0) FROM dh_supply_quota_balance",Long.class); }

    @Test void portalAndGatewayUseOneBalanceAndReceiptReplaysWithoutFreeDataRead() {
        jdbc.execute(singleTenantMigration);
        var sources=List.of(source("asset:apply:1","CALLS",2,"name"));
        var first=service.reserve(req("PORTAL:7:4","request-0001",20,sources));
        commit(first,3); commit(first,3);
        assertThatThrownBy(() -> service.reserve(req("PORTAL:7:4","request-0001",20,sources))).hasMessageContaining("已登记");
        commit(service.reserve(req("API:3:5","request-0002",100,sources)),0);
        assertThat(used()).isEqualTo(2); assertThat(reserved()).isZero();
        assertThatThrownBy(() -> service.reserve(req("PORTAL:7:4","request-0003",20,sources))).hasMessageContaining("额度不足");
        assertThat(service.receipt(first.consumerKey(),first.requestKey(),7).token()).isNull();
        assertThat(service.balances(7)).hasSize(1);
        assertThat(service.balances(7).getFirst().remainingUnits()).isZero();
        assertThat(service.balances(8)).isEmpty();
    }
    @Test void rowReservationLimitsExecutionAndRefundsUnusedRows() {
        var sources=List.of(source("asset:apply:1","ROWS",7,"name"));
        var first=service.reserve(req("PORTAL:7:4","request-0001",100,sources));
        assertThat(first.rowLimit()).isEqualTo(7); assertThat(reserved()).isEqualTo(7);
        commit(first,3); assertThat(used()).isEqualTo(3); assertThat(reserved()).isZero();
        var second=service.reserve(req("API:3:5","request-0002",100,sources));
        assertThat(second.rowLimit()).isEqualTo(4);
        assertThatThrownBy(() -> commit(second,5)).hasMessageContaining("计量参数无效");
        commit(second,4); assertThat(used()).isEqualTo(7);
    }
    @Test void cancelExpiryAndLateWorkerCannotDoubleReleaseOrReturnResults() {
        var sources=List.of(source("asset:apply:1","CALLS",1,"name"));
        var first=service.reserve(req("PORTAL:7:4","request-0001",10,sources));
        service.settle(new Settle(first.consumerKey(),first.requestKey(),first.token(),null,true));
        service.settle(new Settle(first.consumerKey(),first.requestKey(),first.token(),null,true));
        assertThat(reserved()).isZero(); assertThatThrownBy(() -> commit(first,1)).hasMessageContaining("失效");
        var next=service.reserve(req("API:3:5","request-0002",10,sources));
        jdbc.update("UPDATE dh_supply_quota_receipt SET expires_at=clock_timestamp()-interval '1 second' WHERE state='RESERVED'");
        assertThat(service.expire()).isEqualTo(1); assertThat(service.expire()).isZero();
        assertThatThrownBy(() -> commit(next,1)).hasMessageContaining("失效");
        assertThat(used()).isZero(); assertThat(reserved()).isZero();
    }
    @Test void concurrentNodesCannotOverspend() throws Exception {
        var sources=List.of(source("asset:apply:1","CALLS",3,"name"));
        AtomicInteger success=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) { final int id=i; futures.add(pool.submit(() -> {
                try { start.await(); var r=service.reserve(req("API:3:5","concurrent-"+id,5,sources)); commit(r,1); success.incrementAndGet(); }
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException ex) { assertThat(ex.getMessage()).contains("额度不足"); }
                catch(InterruptedException ex) { throw new RuntimeException(ex); } finally { }
            })); }
            start.countDown(); for(var f:futures) f.get(20, TimeUnit.SECONDS);
        }
        assertThat(success).hasValue(3); assertThat(used()).isEqualTo(3); assertThat(reserved()).isZero();
    }
    @Test void policyMutationAndForeignActorReceiptAreRejected() {
        var sources=List.of(source("asset:apply:1","CALLS",2,"name"));
        var first=service.reserve(req("PORTAL:7:4","request-0001",10,sources)); commit(first,1);
        assertThatThrownBy(() -> service.reserve(req("API:3:5","request-0002",10,List.of(source("asset:apply:1","CALLS",10,"name"))))).hasMessageContaining("禁止重置");
        assertThatThrownBy(() -> service.receipt(first.consumerKey(),first.requestKey(),8)).hasMessageContaining("无效");
        assertThat(service.history(7)).hasSize(1);
        commit(first,1); assertThat(used()).isEqualTo(1);
    }
    @Test void unlimitedOverlappingGrantIsNotBlockedOrChargedByExhaustedGrant() {
        var finite=source("asset:apply:1","CALLS",1,"name");
        commit(service.reserve(req("API:3:5","request-0001",10,List.of(finite))),1);
        var unlimited=source("asset:apply:2",null,0,"name");
        var next=service.reserve(req("API:3:5","request-0002",10,List.of(finite,unlimited)));
        assertThat(next.allocations()).isEmpty(); commit(next,9); assertThat(used()).isEqualTo(1);
    }
    @Test void failedConfirmationRollsBackBothBalanceAndReceipt() {
        var sources=List.of(source("asset:apply:1","ROWS",5,"name"));
        var r=service.reserve(req("API:3:5","request-0001",5,sources));
        var broken=org.mockito.Mockito.spy(mapper);
        org.mockito.Mockito.doReturn(0).when(broken).finish("API:3:5","request-0001","COMMITTED",3);
        ReflectionTestUtils.setField(service,"mapper",broken);
        assertThatThrownBy(() -> commit(r,3)).hasMessageContaining("已登记");
        assertThat(used()).isZero(); assertThat(reserved()).isEqualTo(5);
        assertThat(service.receipt("API:3:5","request-0001",7).state()).isEqualTo("RESERVED");
    }
}
