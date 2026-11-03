package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileRunMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileUploadMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用交付迁移和实际 Mapper 在隔离 PostgreSQL 中验证执行围栏与文件保留契约。 */
@Timeout(30)
class BatchFileRunPostgreSqlTest {
    private String url;
    private String user;
    private String password;
    private String schema;
    private JdbcTemplate jdbc;
    private BatchFileRunMapper runs;
    private BatchFileUploadMapper uploads;

    @BeforeEach
    void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "需要显式隔离 PostgreSQL 地址");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/batch_file_[a-z0-9_]+"),
                "只允许独立本机批量文件测试库");
        user = System.getProperty("datahub.test.postgres.user", "codex_test");
        password = System.getProperty("datahub.test.postgres.password", "");
        schema = "batch_file_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url, user, password)).execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema + "&connectTimeout=5&socketTimeout=10", user, password);
        jdbc = new JdbcTemplate(source);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations/0192_dataforge_batch-file-import.sql"))) root = root.getParent();
        assertNotNull(root);
        String migration = Files.readString(root.resolve("sql/postgresql/migrations/0192_dataforge_batch-file-import.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_batch_file_task_seq"),
                migration.indexOf("COMMENT ON TABLE infra_managed_file")));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("batch-file-postgres", new SpringManagedTransactionFactory(), source));
        config.addMapper(BatchFileRunMapper.class);
        config.addMapper(BatchFileUploadMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        runs = session.getMapper(BatchFileRunMapper.class);
        uploads = session.getMapper(BatchFileUploadMapper.class);
        jdbc.update("INSERT INTO dh_batch_file_task(id,task_name,responsible_user_id,responsible_name,responsible_phone,"
                + "target_database_id,target_database_name,config_json,file_count,target_count) VALUES(1,'测试任务',1,'测试负责人','测试电话',1,'测试数据源','{}',1,1)");
    }

    @AfterEach
    void cleanup() {
        if (schema != null && schema.matches("batch_file_test_[0-9a-f]{32}")) {
            new JdbcTemplate(new DriverManagerDataSource(url, user, password)).execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void competingWorkersOnlyClaimOnceAndWrongTokenCannotCommit() throws Exception {
        insert(1, "QUEUED", "{}");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var claims = List.of(workers.submit(() -> { start.await(); return runs.claim(1L, "worker-a"); }),
                    workers.submit(() -> { start.await(); return runs.claim(1L, "worker-b"); }));
            start.countDown();
            assertEquals(1, claims.get(0).get(10, TimeUnit.SECONDS) + claims.get(1).get(10, TimeUnit.SECONDS));
        }
        String token = jdbc.queryForObject("SELECT lease_token FROM dh_batch_file_run WHERE id=1", String.class);
        assertEquals(0, runs.progress(1L, "wrong-token", "[]", 1, 0, 5));
        assertEquals(0, runs.finish(1L, "wrong-token", "SUCCESS", "[]", 1, 0, 5, null));
        assertEquals(1, runs.progress(1L, token, "[]", 1, 0, 5));
        assertEquals(1, runs.finish(1L, token, "SUCCESS", "[]", 1, 0, 5, null));
        assertEquals(0, runs.claim(1L, "again"));
        assertEquals(0, runs.countActive(1L));
    }

    @Test
    void expiredWorkerCannotRenewOrCommitAndUnknownBlocksUntilReviewed() {
        insert(1, "QUEUED", "{}");
        assertEquals(1, runs.claim(1L, "old-worker"));
        jdbc.update("UPDATE dh_batch_file_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=1");
        assertEquals(0, runs.heartbeat(1L, "old-worker"));
        assertEquals(0, runs.progress(1L, "old-worker", "[]", 1, 0, 5));
        assertEquals(0, runs.finish(1L, "old-worker", "SUCCESS", "[]", 1, 0, 5, null));
        assertEquals(1, runs.recoverExpired());
        assertEquals(0, runs.recoverExpired());
        assertEquals(1, runs.countActive(1L));
        assertThrows(DataIntegrityViolationException.class, () -> insert(2, "QUEUED", "{}"));
        jdbc.update("UPDATE dh_batch_file_run SET resolved_time=clock_timestamp(),resolved_by=1,resolution_reason='已停止并核对目标' WHERE id=1");
        assertEquals(0, runs.countActive(1L));
        insert(2, "QUEUED", "{}");
        assertEquals(1, runs.countActive(1L));
    }

    @Test
    void requestKeysAndActiveTaskConstraintRemainUniqueAcrossConnections() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var accepted = List.of(workers.submit(() -> { start.await(); return tryInsert(1); }),
                    workers.submit(() -> { start.await(); return tryInsert(2); }));
            start.countDown();
            assertEquals(1, accepted.get(0).get(10, TimeUnit.SECONDS) + accepted.get(1).get(10, TimeUnit.SECONDS));
        }
        jdbc.update("UPDATE dh_batch_file_run SET status='SUCCESS'");
        String requestId = jdbc.queryForObject("SELECT request_id FROM dh_batch_file_run", String.class);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO dh_batch_file_run(id,task_id,request_id,execution_user_id,config_json,status,file_count,items_json) VALUES(3,1,?,1,'{}','SUCCESS',1,'[]')", requestId));
    }

    @Test
    void retainedFileLookupPreservesLargeIdsAndUnresolvedRuns() {
        long fileId = 9007199254740993L;
        insert(1, "SUCCESS", "{\"files\":[{\"id\":\"9007199254740993\"}]}");
        assertEquals(1, runs.countRetainedFile(fileId));
        assertEquals(0, runs.countRetainedFile(fileId - 1));
        jdbc.update("UPDATE dh_batch_file_run SET create_time=clock_timestamp()-interval '31 days' WHERE id=1");
        assertEquals(0, runs.countRetainedFile(fileId));
        jdbc.update("UPDATE dh_batch_file_run SET status='UNKNOWN' WHERE id=1");
        assertEquals(1, runs.countRetainedFile(fileId));
        jdbc.update("UPDATE dh_batch_file_run SET resolved_time=clock_timestamp() WHERE id=1");
        assertEquals(0, runs.countRetainedFile(fileId));
        jdbc.update("UPDATE dh_batch_file_run SET config_json='{\"files\":[{\"id\":9007199254740993}]}',create_time=clock_timestamp() WHERE id=1");
        assertEquals(1, runs.countRetainedFile(fileId));
    }

    @Test
    void deliveredConstraintsRejectMalformedSnapshotsAndAcceptCleanupState() {
        assertThrows(DataIntegrityViolationException.class, () -> insert(1, "SUCCESS", "[]"));
        assertThrows(DataIntegrityViolationException.class, () -> insert(1, "SUCCESS", "invalid"));
        jdbc.update("INSERT INTO dh_batch_file_upload(owner_id,operation_key,name,file_type,source,size,sha256,state,expires_at) VALUES(1,'cleanup-test','test.csv','csv','LOCAL',10,'digest','CLEANUP_PENDING',clock_timestamp())");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_batch_file_upload WHERE state='CLEANUP_PENDING'", Integer.class));
    }

    @Test
    void lateUploadReceiptCannotReviveExpiredBoundOrCleaningFile() {
        jdbc.update("INSERT INTO dh_batch_file_upload(id,owner_id,operation_key,name,file_type,source,size,sha256,state,expires_at) "
                + "VALUES(10,1,'late-receipt','test.csv','csv','LOCAL',10,'digest','PENDING',clock_timestamp()+interval '1 hour')");
        assertEquals(1, uploads.ready(10L, 20L, "1"));
        assertEquals(0, uploads.ready(10L, 21L, "1"));
        jdbc.update("UPDATE dh_batch_file_upload SET state='PENDING',expires_at=clock_timestamp()-interval '1 second' WHERE id=10");
        assertEquals(0, uploads.ready(10L, 21L, "1"));
        assertEquals(List.of(10L), uploads.selectExpired(0).stream().map(row -> row.getId()).toList());
        assertTrue(uploads.selectExpired(10).isEmpty(), "游标应越过失败待清理项");
        jdbc.update("UPDATE dh_batch_file_upload SET expires_at=clock_timestamp()+interval '1 hour',task_id=1 WHERE id=10");
        assertEquals(0, uploads.ready(10L, 21L, "1"));
        jdbc.update("UPDATE dh_batch_file_upload SET state='CLEANUP_PENDING' WHERE id=10");
        assertEquals(0, uploads.completeCleanup(10L), "已绑定素材不允许旧清理回执覆盖");
        jdbc.update("UPDATE dh_batch_file_upload SET task_id=NULL WHERE id=10");
        assertEquals(0, uploads.ready(10L, 21L, "1"));
        assertEquals(1, uploads.completeCleanup(10L));
        assertEquals(0, uploads.ready(10L, 21L, "1"));
        assertEquals("CLEANED", jdbc.queryForObject("SELECT state FROM dh_batch_file_upload WHERE id=10", String.class));
    }

    private int tryInsert(long id) {
        try { insert(id, "QUEUED", "{}"); return 1; }
        catch (DataIntegrityViolationException expected) { return 0; }
    }

    private void insert(long id, String state, String config) {
        jdbc.update("INSERT INTO dh_batch_file_run(id,task_id,request_id,execution_user_id,config_json,status,file_count,items_json) VALUES(?,1,?,1,?,?,1,'[]')",
                id, UUID.randomUUID().toString(), config, state);
    }
}
