package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCommandMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessRunAuthorization;
import cn.iocoder.yudao.module.dataforge.service.access.AccessRunCoordinator;
import cn.iocoder.yudao.module.dataforge.service.access.DataAccessExecutor;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlRunCoordinator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only process control and external-effect simulator; all run transitions use production coordinators. */
final class DistributedRunFaultSupport implements AutoCloseable {
    static final LocalDateTime SCHEDULED = LocalDateTime.of(2026, 1, 1, 0, 0);
    static final String MIGRATION = "sql/postgresql/migrations/0038_dataforge_run-protocol.sql";
    static final String SCHEMA_PATTERN = "distributed_fault_[a-f0-9]{32}";
    static final long ACTOR = 99L;

    enum Domain {
        ETL("dh_dataforge_etl_record", "dh_dataforge_etl_pipeline", "pipeline_id", 6, 5),
        ACCESS("dh_dataforge_access_task", "dh_dataforge_access", "data_access_id", 5, 6);
        final String runs;
        final String tasks;
        final String taskColumn;
        final int unknown;
        final int stopped;
        Domain(String runs, String tasks, String taskColumn, int unknown, int stopped) {
            this.runs = runs; this.tasks = tasks; this.taskColumn = taskColumn;
            this.unknown = unknown; this.stopped = stopped;
        }
    }

    final String url;
    final String schema = "distributed_fault_" + UUID.randomUUID().toString().replace("-", "");
    final JdbcTemplate admin;
    final JdbcTemplate db;
    final List<Node> nodes = new ArrayList<>();
    Runtime runtime;
    private boolean created;
    private long sequence;
    private final Path logs;

    DistributedRunFaultSupport(String url) throws Exception {
        validate(url, schema);
        this.url = url;
        admin = boundedJdbc(boundedSource(url, null));
        db = boundedJdbc(source(url, schema));
        logs = Path.of(System.getProperty("datahub.test.fault.logDir", System.getProperty("java.io.tmpdir")))
                .resolve(schema);
        try {
            admin.execute("CREATE SCHEMA " + schema);
            created = true;
            verifySchema(db, schema);
            createFixture();
            Files.createDirectories(logs);
            runtime = new Runtime(url, schema);
        } catch (Exception | AssertionError failure) {
            try { close(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void validate(String url, String schema) {
        // No host aliases, query parameters, socket factories, credentials, or caller-controlled search_path.
        if (url == null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+")
                || schema == null || !schema.matches(SCHEMA_PATTERN)) {
            throw new IllegalArgumentException("Only explicit loopback PostgreSQL and owned fault-test schemas are allowed");
        }
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid loopback port");
    }

    static DriverManagerDataSource source(String url, String schema) {
        validate(url, schema);
        return boundedSource(url, schema);
    }

    private static DriverManagerDataSource boundedSource(String url, String schema) {
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "10");
        // Each NEW connection gets server-side bounds, including administrative CREATE/DROP.
        // Query cancellation and the socket deadline independently bound a stalled SQL/response.
        properties.setProperty("options", "-c statement_timeout=5000 -c lock_timeout=1000");
        if (schema != null) properties.setProperty("currentSchema", schema);
        var source = new DriverManagerDataSource(url, System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        source.setConnectionProperties(properties);
        return source;
    }

    private static JdbcTemplate boundedJdbc(DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(6);
        return jdbc;
    }

    static void verifySchema(JdbcTemplate db, String schema) {
        if (!schema.equals(db.queryForObject("SELECT current_schema()", String.class)))
            throw new IllegalStateException("Test connection did not select its owned schema");
    }

    private void createFixture() throws Exception {
        // Same four-table, pre-0038 fixture as EtlRunCoordinatorPostgreSqlTest; ACCESS adds its used columns.
        db.execute("CREATE TABLE dh_dataforge_access(id bigint primary key,tenant_id bigint,deleted smallint default 0,"
                + "task_name varchar(100),description text,org_id bigint,engine_type smallint,source_type smallint,"
                + "source_database_id bigint,target_database_id bigint,access_mode smallint,task_type smallint,cron varchar(200),"
                + "status smallint,run_status smallint,data_access_json jsonb,seatunnel_config text,config_format varchar(20),"
                + "api_access_json jsonb,start_time timestamp,end_time timestamp,release_time timestamp,release_user_id bigint,"
                + "next_run_time timestamp,remark text,creator varchar(64),updater varchar(64),"
                + "create_time timestamp default now(),update_time timestamp default now())");
        db.execute("CREATE TABLE dh_dataforge_access_task(id bigint primary key,data_access_id bigint,tenant_id bigint,"
                + "run_status smallint,deleted smallint default 0,task_name varchar(100),run_type smallint,execute_no varchar(100),"
                + "seatunnel_job_id varchar(100),start_time timestamp,end_time timestamp,real_time_log text,error_info text,"
                + "data_count bigint,table_success_count bigint,table_fail_count bigint,byte_count varchar(50),org_id bigint,"
                + "creator varchar(64),updater varchar(64),create_time timestamp default now(),update_time timestamp default now())");
        db.execute("CREATE TABLE dh_dataforge_etl_pipeline(environment_id bigint,id bigint primary key,tenant_id bigint,name varchar(50),task_type smallint,cron varchar(200),"
                + "next_run_time timestamp,status smallint,schedule_status smallint,run_status smallint,start_time timestamp,end_time timestamp,"
                + "release_time timestamp,release_user_id bigint,description text,nodes_json jsonb,edges_json jsonb,params_json jsonb,org_id bigint,"
                + "creator varchar(64),updater varchar(64),create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        db.execute("CREATE TABLE dh_dataforge_etl_record(id bigint primary key,pipeline_id bigint,tenant_id bigint,name varchar(50),run_status smallint,execute_type smallint,"
                + "start_time timestamp,end_time timestamp,log text,config_json jsonb,org_id bigint,creator varchar(64),updater varchar(64),"
                + "create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate checked-out 0038 migration");
        db.execute(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve(MIGRATION)).replace("datahub-center", schema));

        String capacityMigration = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0049_dataforge_run-datasource-capacity.sql"))
                .replace("datahub-center", schema);
        db.execute(capacityMigration);
        db.execute(capacityMigration); // 编号迁移必须可重放，不能只靠静态脚本检查。
        db.execute(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0051_dataforge_sync-batch-evidence.sql"))
                .replace("datahub-center", schema));
        db.execute(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0053_dataforge_sync-submission-boundary.sql"))
                .replace("datahub-center", schema));
        db.execute("CREATE TABLE dh_dataforge_access_task_detail(id bigint primary key,data_access_id bigint,task_id bigint,"
                + "source_schema_name varchar(100),source_table_name varchar(100),target_schema_name varchar(100),target_table_name varchar(100),"
                + "sync_count bigint,sync_bytes varchar(50),if_success smallint,error_msg text,success_msg text,org_id bigint,"
                + "tenant_id bigint default 1,creator varchar(64),updater varchar(64),create_time timestamp,update_time timestamp,deleted smallint default 0)");
        db.execute("CREATE TABLE dh_dataforge_access_increase_log(id bigint primary key,data_access_id bigint,source_database_id bigint,"
                + "target_database_id bigint,schema_name varchar(100),table_name varchar(100),increase_column varchar(100),"
                + "start_val text,end_val text,access_mode smallint,tenant_id bigint default 1,creator varchar(64),updater varchar(64),"
                + "create_time timestamp,update_time timestamp,deleted smallint default 0)");
        db.execute("CREATE TABLE fault_owner(schema_name text primary key)");
        db.update("INSERT INTO fault_owner VALUES (?)", schema);
        db.execute("CREATE TABLE fault_command(id bigint primary key,node text,action text,domain text,record_id bigint,tenant_id bigint,payload text)");
        db.execute("CREATE TABLE fault_event(node text,command_id bigint,phase text,detail text,pid bigint,"
                + "PRIMARY KEY(node,command_id,phase))");
        db.execute("CREATE TABLE fault_gate(name text primary key)");
        // Deliberately NO run/batch uniqueness: a duplicate external submission must remain observable.
        db.execute("CREATE TABLE fault_engine_commit(id bigserial primary key,domain text,record_id bigint,tenant_id bigint,batch integer)");
        db.execute("CREATE TABLE fault_local_write(id bigserial primary key,record_id bigint,tenant_id bigint)");
    }

    void seed(Domain domain, long task, long tenant) {
        if (domain == Domain.ETL) {
            db.update("INSERT INTO dh_dataforge_etl_pipeline(id,name,task_type,status,schedule_status,revision,published_revision,schedule_generation,published_execution_user_id,published_config_json,next_run_time) VALUES(?,'fixture',2,1,1,2,1,3,99,CAST(? AS jsonb),?)", task,
                    "{\"name\":\"published\",\"revision\":1,\"nodes\":[],\"edges\":[],\"parameters\":[],\"cron\":\"0 * * * * *\"}", SCHEDULED);
        } else {
            db.update("INSERT INTO dh_dataforge_access(id,task_name,engine_type,task_type,status,revision,published_revision,schedule_generation,published_execution_user_id,published_config_json,next_run_time,cron,creator) VALUES(?,'fixture',1,3,1,1,1,3,99,CAST(? AS jsonb),?,'0 * * * * *','99')", task,
                    "{\"id\":" + task + ",\"taskName\":\"fixture\",\"engineType\":1,\"taskType\":3,"
                            + "\"sourceDatabaseId\":101,\"targetDatabaseId\":102,\"cron\":\"0 * * * * *\"}", SCHEDULED);
        }
    }

    long enqueue(Domain domain, long task, long tenant, String request) {
        identity(tenant);
        try { return runtime.enqueue(domain, task, request).recordId(); }
        finally { clearIdentity(); }
    }

    List<Node> startPair() throws Exception {
        // Start both before waiting: these are OS processes, not test threads or two beans in one JVM.
        Node a = start("a"), b = start("b");
        a.await(0, "READY"); b.await(0, "READY");
        assertNotEquals(a.process.pid(), b.process.pid());
        assertNotEquals(ProcessHandle.current().pid(), a.process.pid());
        return List.of(a, b);
    }

    private Node start(String label) throws Exception {
        String name = label + "-" + UUID.randomUUID();
        Path log = logs.resolve(label + ".log"), args = logs.resolve(label + ".args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(args, "-Ddatahub.test.postgres.user=" + System.getProperty("datahub.test.postgres.user", "dataforge_test") + "\n-cp\n" + quote(classpath) + "\n" + DistributedRunTestNode.class.getName()
                + "\n" + quote(url) + "\n" + schema + "\n" + name + "\n");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "@" + args)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Node node = new Node(name, process, log, args);
        nodes.add(node);
        return node;
    }

    private static String quote(String value) {
        if (value.contains("\n") || value.contains("\r") || value.contains("\"")) throw new IllegalArgumentException("Unsafe JVM argument");
        return "\"" + value.replace('\\', '/') + "\"";
    }

    final class Node {
        final String name;
        final Process process;
        final Path log;
        final Path args;
        Node(String name, Process process, Path log, Path args) { this.name = name; this.process = process; this.log = log; this.args = args; }
        long send(String action, Domain domain, long record, long tenant, String payload) {
            long id = ++sequence;
            db.update("INSERT INTO fault_command VALUES (?,?,?,?,?,?,?)", id, name, action, domain.name(), record, tenant, payload);
            return id;
        }
        String await(long command, String phase) {
            waitFor("node " + name + " " + phase + " (log: " + log + ")", () -> {
                if (count("SELECT count(*) FROM fault_event WHERE node=? AND command_id=? AND phase='ERROR'", name, command) > 0)
                    throw new AssertionError("Child command failed: " + log);
                boolean arrived = count("SELECT count(*) FROM fault_event WHERE node=? AND command_id=? AND phase=?", name, command, phase) == 1;
                if (!arrived && !process.isAlive()) throw new AssertionError("Child exited " + process.exitValue() + ": " + log);
                return arrived;
            });
            return db.queryForObject("SELECT detail FROM fault_event WHERE node=? AND command_id=? AND phase=?", String.class, name, command, phase);
        }
        String call(String action, Domain domain, long record, long tenant, String payload) {
            return await(send(action, domain, record, tenant, payload), "DONE");
        }
        void kill() throws Exception {
            assertTrue(process.isAlive(), "Fault requires a live child JVM");
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned child did not exit");
        }
    }

    long count(String sql, Object... values) { return Objects.requireNonNull(db.queryForObject(sql, Long.class, values)); }
    Map<String, Object> run(Domain domain, long record) { return db.queryForMap("SELECT * FROM " + domain.runs + " WHERE id=?", record); }
    long commits(Domain domain, long record) { return count("SELECT count(*) FROM fault_engine_commit WHERE domain=? AND record_id=?", domain.name(), record); }
    void open(String gate) { db.update("INSERT INTO fault_gate VALUES (?) ON CONFLICT DO NOTHING", gate); }
    void awaitExpired(Domain domain, long record) {
        waitFor("real PostgreSQL lease expiry", () -> count("SELECT count(*) FROM " + domain.runs + " WHERE id=? AND lease_until <= clock_timestamp()", record) == 1);
    }
    void awaitRetry(Domain domain, long record) {
        waitFor("real PostgreSQL retry deadline", () -> count("SELECT count(*) FROM " + domain.runs + " WHERE id=? AND next_attempt_at <= clock_timestamp()", record) == 1);
    }
    static void waitFor(String description, BooleanSupplier ready) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timed out waiting for " + description);
            try { Thread.sleep(40); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }

    @Override public void close() throws Exception {
        Throwable failure = null; // Only Exception and AssertionError are aggregated, never VM fatal errors.
        boolean interrupted = Thread.interrupted();
        try {
            for (Node node : nodes) {
                try {
                    if (node.process.isAlive()) {
                        node.process.destroyForcibly();
                        if (!node.process.waitFor(10, TimeUnit.SECONDS))
                            throw new AssertionError("Owned child did not exit within ten seconds: " + node.name);
                    }
                } catch (Exception | AssertionError problem) {
                    if (problem instanceof InterruptedException) {
                        interrupted = true;
                        Thread.interrupted(); // Permit bounded best-effort cleanup of the remaining owned children.
                    }
                    failure = aggregate(failure, problem);
                }
                try {
                    // Retain diagnostics for a child whose exit has not been confirmed.
                    if (!node.process.isAlive()) Files.deleteIfExists(node.args);
                } catch (Exception | AssertionError problem) {
                    failure = aggregate(failure, problem);
                }
            }
            boolean childrenExited = true;
            for (Node node : nodes) {
                try {
                    if (node.process.isAlive()) childrenExited = false;
                } catch (Exception | AssertionError problem) {
                    childrenExited = false; // Unknown liveness is not evidence that DROP is safe.
                    failure = aggregate(failure, problem);
                }
            }
            try {
                if (runtime != null) runtime.close();
            } catch (Exception | AssertionError problem) {
                failure = aggregate(failure, problem);
            }
            if (!childrenExited) {
                failure = aggregate(failure, new IllegalStateException("Owned child still alive or exit unconfirmed; retaining schema and logs for diagnosis"));
            } else if (created) {
                try {
                    validate(url, schema);
                    verifySchema(db, schema);
                    // Keep created=true on timeout/failure so a later bounded close can retry this owned schema.
                    admin.execute("DROP SCHEMA " + schema + " CASCADE");
                    created = false;
                } catch (Exception | AssertionError problem) {
                    failure = aggregate(failure, problem);
                }
            }
            if (failure instanceof AssertionError assertion) throw assertion;
            if (failure instanceof Exception exception) throw exception;
        } finally {
            clearIdentity();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Throwable aggregate(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    static void identity(long tenant) {
        TenantContextHolder.setTenantId(tenant);
        TenantContextHolder.setIgnore(false);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new LoginUser().setId(ACTOR), null));
    }
    static void clearIdentity() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    /** Minimal isolated runtime: real MyBatis, Spring ETL proxy, and ACCESS REQUIRES_NEW transactions. */
    static final class Runtime implements AutoCloseable {
        final AnnotationConfigApplicationContext app;
        private final com.zaxxer.hikari.HikariDataSource pool;
        final JdbcTemplate db;
        final EtlPipelineMapper pipelines;
        final EtlRecordMapper records;
        final DataAccessMapper accessTasks;
        final DataAccessTaskMapper accessRuns;

        final DataAccessTaskDetailMapper details;
        final DataAccessIncreaseLogMapper watermarks;
        final EtlRunCoordinator etl;
        final AccessRunCoordinator access;
        final RunExecutionProperties properties = new RunExecutionProperties();
        final io.micrometer.core.instrument.simple.SimpleMeterRegistry metricsRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        Runtime(String url, String schema) {
            // Independent child JVMs poll frequently; opening a socket for every query exhausts Windows ports.
            // Keep the existing per-connection bounds and schema validation; only reuse physical connections.
            pool = new com.zaxxer.hikari.HikariDataSource();
            pool.setDataSource(source(url, schema));
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(5000);
            pool.setValidationTimeout(2000);
            app = new AnnotationConfigApplicationContext();
            try {
                var source = pool;
                db = boundedJdbc(source);
                verifySchema(db, schema);
                if (!schema.equals(db.queryForObject("SELECT schema_name FROM fault_owner", String.class)))
                    throw new IllegalStateException("Missing owned fixture marker");
                var config = new MybatisConfiguration();
                config.setDefaultStatementTimeout(6);
                // SQL bind logging would expose the synthetic execution tokens in retained fault logs.
                config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
                GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
                config.setMapUnderscoreToCamelCase(true);
                config.setLocalCacheScope(LocalCacheScope.STATEMENT);
                config.setEnvironment(new Environment("distributed-fault", new SpringManagedTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
                for (Class<?> mapper : List.of(EtlPipelineMapper.class, EtlRecordMapper.class, DataAccessMapper.class,
                        DataAccessTaskMapper.class, RunCommandMapper.class, DataAccessTaskDetailMapper.class, DataAccessIncreaseLogMapper.class)) config.addMapper(mapper);
                var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
                pipelines = sessions.getMapper(EtlPipelineMapper.class); records = sessions.getMapper(EtlRecordMapper.class);
                accessTasks = sessions.getMapper(DataAccessMapper.class); accessRuns = sessions.getMapper(DataAccessTaskMapper.class);

                details = sessions.getMapper(DataAccessTaskDetailMapper.class); watermarks = sessions.getMapper(DataAccessIncreaseLogMapper.class);
                properties.setLease(Duration.ofSeconds(8)); properties.setHeartbeat(Duration.ofSeconds(2)); properties.setStopPoll(Duration.ofSeconds(1));
                assertTrue(properties.isTimingValid());
                var manager = new DataSourceTransactionManager(source);
                app.register(Transactions.class);
                app.registerBean(DataSource.class, () -> source);
                app.registerBean(PlatformTransactionManager.class, () -> manager);
                app.registerBean(EtlPipelineMapper.class, () -> pipelines); app.registerBean(EtlRecordMapper.class, () -> records);
                app.registerBean(RunExecutionProperties.class, () -> properties);
                app.getBeanFactory().registerSingleton("qualityBatches", org.mockito.Mockito.mock(
                        cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlQualityBatchMapper.class));
                app.getBeanFactory().registerSingleton("targetBatches", org.mockito.Mockito.mock(
                        cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlTargetBatchMapper.class));
                app.getBeanFactory().registerSingleton("replays", org.mockito.Mockito.mock(
                        cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlReplayMapper.class));
                app.getBeanFactory().registerSingleton("versionService", org.mockito.Mockito.mock(
                        cn.iocoder.yudao.module.dataforge.service.etl.EtlVersionService.class));
                app.getBeanFactory().registerSingleton("archives", org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlArchiveMapper.class));

                app.registerBean(EtlRunCoordinator.class);
                app.registerBean(RunMetrics.class, () -> new RunMetrics(metricsRegistry, properties));
                app.refresh();
                etl = app.getBean(EtlRunCoordinator.class);
                assertTrue(AopUtils.isAopProxy(etl), "ETL must run through a real Spring transactional proxy");
                access = new AccessRunCoordinator();
                ReflectionTestUtils.setField(access, "dataSource", source);
                ReflectionTestUtils.setField(access, "groupRewriteCoordinator", org.mockito.Mockito.mock(
                        cn.iocoder.yudao.module.dataforge.service.access.AccessGroupRewriteCoordinator.class));
                ReflectionTestUtils.setField(access, "tasks", accessTasks); ReflectionTestUtils.setField(access, "runs", accessRuns);
                ReflectionTestUtils.setField(access, "commands", sessions.getMapper(RunCommandMapper.class));
                ReflectionTestUtils.setField(access, "properties", properties);
                ReflectionTestUtils.setField(access, "metrics", app.getBean(RunMetrics.class));
                // External permission/database authorization is not under test; it never contacts a live service.
                ReflectionTestUtils.setField(access, "authorization", new AccessRunAuthorization() {
                    @Override public void action(DataAccessDO task, Long user, String permission) { assertEquals(ACTOR, user); assertFalse(TenantContextHolder.isIgnore()); }
                    @Override public void execution(DataAccessDO snapshot, Long user) { assertEquals(ACTOR, user); assertFalse(TenantContextHolder.isIgnore()); }
                });
                access.configureTransactions(manager);
            } catch (RuntimeException | Error failure) {
                try { app.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                pool.close();
                metricsRegistry.close();
                throw failure;
            }
        }

        EtlRecordDO prepared(long task) {
            return EtlRecordDO.builder().pipelineId(task).pipelineRevision(1).name("published").executionUserId(ACTOR)
                    .configJson("{\"name\":\"published\",\"nodes\":[],\"edges\":[],\"parameters\":[]}").build();
        }
        RunReceipt enqueue(Domain domain, long task, String request) {
            return domain == Domain.ETL ? etl.enqueueManual(pipelines.selectById(task), prepared(task), request) : access.enqueueManual(task, request);
        }
        Optional<RunLease> claim(Domain domain, long id, String node) { return domain == Domain.ETL ? etl.claim(id, node) : access.claim(id, node); }
        boolean start(Domain domain, RunLease lease) {
            return domain == Domain.ETL ? etl.startEffects(lease.recordId(), lease.token()) : access.startEffects(lease, RunProtocol.Stage.EXECUTION_STARTED);
        }
        boolean renew(Domain domain, RunLease lease) { return domain == Domain.ETL ? etl.renew(lease.recordId(), lease.token()) : access.renew(lease); }
        boolean stopRequested(Domain domain, RunLease lease) { return domain == Domain.ETL ? etl.shouldStop(lease.recordId(), lease.token()) : access.shouldStop(lease); }
        boolean write(Domain domain, RunLease lease, Runnable callback) { return domain == Domain.ETL ? etl.writeOwned(lease.recordId(), lease.token(), callback) : access.writeOwned(lease, callback); }
        boolean finish(Domain domain, RunLease lease, int status, RunProtocol.Effect effect, boolean terminated) {
            return domain == Domain.ETL ? etl.finish(lease.recordId(), lease.token(), status, effect, terminated, "simulator evidence")
                    : access.finish(lease, DataAccessTaskDO.builder().runStatus(status).dataCount(1L).tableSuccessCount(0L).tableFailCount(0L).build(), terminated, effect);
        }
        RunReceipt stop(Domain domain, long id, String request) {
            return domain == Domain.ETL ? etl.stopCommand(id, request, ACTOR) : access.requestStop(id, request, "fixture stop");
        }
        boolean recover(Domain domain, long id) { return domain == Domain.ETL ? etl.recoverExpired(id) : access.recoverExpiredRecord(id); }
        void delete(Domain domain, long id) { if (domain == Domain.ETL) etl.deleteRecord(id, () -> fail("Protected record's children deleted")); else access.deleteRuns(List.of(id)); }

        void rejectInRealExecutor(Domain domain, long id) {
            if (domain == Domain.ETL) {
                var executor = new EtlPipelineExecutor();
                ReflectionTestUtils.setField(executor, "runCoordinator", etl); ReflectionTestUtils.setField(executor, "recordMapper", records);
                ReflectionTestUtils.setField(executor, "executionProperties", properties);
                executor.init();
                try {
                    ((ExecutorService) Objects.requireNonNull(ReflectionTestUtils.getField(executor, "executor"))).shutdownNow();
                    executor.submit(id); // Real ThreadPoolExecutor rejection and production releaseForCapacity handler.
                } finally { executor.destroy(); }
            } else {
                var executor = new DataAccessExecutor();
                ReflectionTestUtils.setField(executor, "coordinator", access); ReflectionTestUtils.setField(executor, "properties", properties);
                executor.init();
                try {
                    ((ExecutorService) Objects.requireNonNull(ReflectionTestUtils.getField(executor, "executor"))).shutdownNow();
                    assertFalse(executor.submit(id));
                } finally { executor.destroy(); }
            }
        }

        /** Invoke the real DB Switch callback consumer: failed partial output must not advance a watermark. */
        void accessTableResult(RunLease lease, boolean success) {
            var task = accessRuns.selectById(lease.recordId());
            task.setExecutionToken(lease.token());
            var executor = new DataAccessExecutor();
            ReflectionTestUtils.setField(executor, "coordinator", access);
            ReflectionTestUtils.setField(executor, "taskMapper", accessRuns);
            ReflectionTestUtils.setField(executor, "taskDetailMapper", details);
            ReflectionTestUtils.setField(executor, "increaseLogMapper", watermarks);
            var result = new DbSwitchTableResult();
            result.setSourceTableName("orders"); result.setTargetTableName("orders");
            result.getIfSuccess().set(success); result.getSyncCount().set(1); result.getSyncBytes().set(8);
            var watermark = DataAccessIncreaseLogDO.builder().dataAccessId(task.getDataAccessId()).tableName("orders")
                    .increaseColumn("id").startVal("0").endVal("2").build();
            ReflectionTestUtils.invokeMethod(executor, "handleTableResult", accessTasks.selectById(task.getDataAccessId()), task,
                    result, new StringBuffer(), Map.of("orders", watermark), new AtomicLong(), new AtomicLong(),
                    new AtomicLong(), new AtomicLong());
        }

        void transactionProbe(Domain domain, RunLease lease, boolean rollback) {
            write(domain, lease, () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                db.update("INSERT INTO fault_local_write(record_id) VALUES (?)", lease.recordId());
                if (rollback) throw new IllegalStateException("injected local callback rollback");
            });
        }
        @Override public void close() {
            try { app.close(); }
            finally { try { pool.close(); } finally { metricsRegistry.close(); } }
        }
    }
}
