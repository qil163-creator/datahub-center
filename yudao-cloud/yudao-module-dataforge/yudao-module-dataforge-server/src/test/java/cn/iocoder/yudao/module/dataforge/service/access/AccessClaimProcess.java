package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.DriverManager;

/** Child JVM, only called by the isolated loopback PG integration test. */
public final class AccessClaimProcess {
    public static void main(String[] args) throws Exception {
        if (!args[0].startsWith("jdbc:postgresql://127.0.0.1:") || !args[1].matches("access_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Only isolated loopback ACCESS test schemas are allowed");
        var source = new DriverManagerDataSource(args[0] + "?currentSchema=" + args[1], System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setEnvironment(new Environment("access-child", new SpringManagedTransactionFactory(), source));
        config.addMapper(DataAccessMapper.class); config.addMapper(DataAccessTaskMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var coordinator = new AccessRunCoordinator();
        ReflectionTestUtils.setField(coordinator, "metrics",
                new cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ReflectionTestUtils.setField(coordinator, "tasks", session.getMapper(DataAccessMapper.class));
        ReflectionTestUtils.setField(coordinator, "runs", session.getMapper(DataAccessTaskMapper.class));
        ReflectionTestUtils.setField(coordinator, "properties", new RunExecutionProperties());
        coordinator.configureTransactions(new DataSourceTransactionManager(source));
        TenantContextHolder.setTenantId(1L);
        try (var connection = source.getConnection()) {
            try (var statement = connection.prepareStatement("INSERT INTO access_claim_barrier(worker) VALUES(?)")) {
                statement.setString(1, args[3]); statement.executeUpdate();
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (true) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM access_claim_barrier")) {
                    rows.next(); if (rows.getInt(1) == 2) break;
                }
                if (System.nanoTime() > deadline) throw new IllegalStateException("Other claim process did not reach barrier");
                Thread.sleep(20);
            }
        }
        System.out.println("ACCESS_CLAIM=" + coordinator.claim(Long.valueOf(args[2]), args[3]).isPresent());
        TenantContextHolder.clear();
    }
}
