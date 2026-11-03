package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实质量入口/SQL/两连接并发提交，审计写入隔离为 mock；不代表副本到数证明。 */
@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityReadSnapshotPostgreSqlTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"visible,true","many,true","route-mismatch,true","future-xid,true",
            "batch-changed,true","cleanup-failure,true","visible,false","many,false","route-mismatch,false",
            "future-xid,false","batch-changed,false","cleanup-failure,false","truncate,true","truncate,false",
            "recreate,true","recreate,false","legacy,true","legacy,false"})
    void actualWriteReceiptIsInspectedInRuleSnapshotAndPersistedOnlyAfterCleanup(String mode, boolean insert) throws Exception {
        String schema = "quality_commit_" + UUID.randomUUID().toString().replace("-", "");
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        try (var reader = open(); var control = open(); var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                reader.setSchema(schema);
                sql.execute("CREATE TABLE " + schema + ".sample(id bigint)");
                var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(
                        source, ProductTypeEnum.POSTGRESQL, insert ? cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT
                                : cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
                writer.prepareWrite(schema, "sample", java.util.List.of("id"));
                var collected = new cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary.Collector();
                long count = mode.equals("many") ? 18 : 1;
                for (int i = 0; i < count; i++) {
                    var receipt = writer.writeWithReceipt(java.util.List.of("id"), java.util.List.<Object[]>of(new Object[]{(long)i})).receipt();
                    assertNotNull(receipt);
                    collected.add(1, new cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt(1,receipt.protocol(),
                            mode.equals("future-xid") ? "18446744073709551615" : receipt.transactionId(),
                            mode.equals("route-mismatch") ? "f".repeat(64) : receipt.routeFingerprint(),
                            mode.equals("legacy") ? null : receipt.relationFingerprint()));
                }
                var summary = collected.snapshot(); var receipt = summary.receipts().getFirst().receipt(); var c = summary.coverage();
                var commits = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO(1,count,count,count > 16,
                        summary.receipts().stream().map(e -> new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.Entry(
                                e.batch(),1,e.receipt().protocol(),e.receipt().transactionId(),e.receipt().routeFingerprint(),e.receipt().relationFingerprint())).toList(),
                        new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.Coverage(1,c.protocol(),
                                c.batchCount(),c.acknowledgedRows(),c.firstTransactionId(),c.lastTransactionId(),c.routeFingerprint(),c.digest(),c.relationFingerprint()));
                if (mode.equals("truncate") || mode.equals("recreate")) {
                    if (mode.equals("truncate")) sql.execute("TRUNCATE " + schema + ".sample");
                    else {
                        sql.execute("DROP TABLE " + schema + ".sample");
                        sql.execute("CREATE TABLE " + schema + ".sample(id bigint)");
                    }
                    // 普通规则仍 PASS，但新行不是旧批次的到数凭证。
                    sql.execute("INSERT INTO " + schema + ".sample VALUES(99)");
                }
                var transfer = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncTableTransferDTO(
                        1, "FULL_SCAN_TABLE", count, count, 1, 1, 0L, false, false, false, commits);
                var batch = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO(1,4L,"fixture",
                        "POSTGRESQL",schema,"sample","sample",false,false,java.util.List.of(
                        new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO.Batch(20L,1,3L,3,
                                false,false,false,"COMPLETE","fixture","TABLE_COPY","CONFIRMED",true,true,count,8L,transfer)));
                var observation = new QualitySyncEvidenceCollector.Observation("OBSERVED",99L,batch);
                var collector = mock(QualitySyncEvidenceCollector.class);
                when(collector.observe(any(),any(),any(),any())).thenReturn(observation,
                        mode.equals("batch-changed") ? new QualitySyncEvidenceCollector.Observation("BATCH_QUERY_UNAVAILABLE",99L,null) : observation);
                var executor = QualityRuntimeFixture.executor();
                ReflectionTestUtils.setField(executor,"syncEvidenceCollector",collector);
                ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
                var mapper = mock(QualityAuditLogMapper.class); ReflectionTestUtils.setField(executor,"auditLogMapper",mapper);
                doAnswer(call -> {
                    var audit = call.getArgument(0, QualityAuditLogDO.class);
                    if (audit.getReadEvidence() != null) { assertTrue(reader.getAutoCommit()); assertFalse(reader.isReadOnly()); }
                    return 1;
                }).when(mapper).insert(any(QualityAuditLogDO.class));
                Connection connection = reader;
                if (mode.equals("cleanup-failure")) connection = (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class[]{Connection.class},(p,m,a)->{
                            if (m.getName().equals("rollback") && (a == null || a.length == 0)) throw new SQLException("fixture private cleanup");
                            try { return m.invoke(reader,a); } catch (InvocationTargetException ex) { throw ex.getCause(); }
                        });
                var route = new MasterDatabaseResolver.ConnectionInfo(); route.setDatabaseId(4L); route.setUsageType(2);
                route.setProductType(ProductTypeEnum.POSTGRESQL); route.setSchema(schema); route.setConfigurationRevision("fixture");
                QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor,10L,
                        QualityTaskDO.builder().id(1L).build(),connection,route,
                        QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName("sample").build(),
                        QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION").paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build());
                assertNotNull(audit); verify(mapper).insert(audit);
                if (mode.equals("cleanup-failure")) {
                    assertEquals(QualityAuditLogDO.RESULT_ERROR,audit.getResultType()); assertNull(audit.getReadEvidence());
                    assertTrue(reader.isClosed()); verify(collector,times(1)).observe(any(),any(),any(),any());
                } else {
                    assertEquals(QualityAuditLogDO.RESULT_PASS,audit.getResultType());
                    var read = JsonUtils.parseObject(audit.getReadEvidence(),QualityReadEvidence.class);
                    assertEquals("UNPROVEN_REPLICA",read.getReadMode()); assertNotNull(read.getCommitScope());
                    if (mode.equals("many")) {
                        assertEquals("COMMIT_RECEIPTS_INCOMPLETE",read.getCommitScope().reason());
                        assertNull(read.getCommitScope().visibility());
                    } else if (mode.equals("batch-changed") || mode.equals("future-xid")) {
                        assertNull(read.getCommitScope().visibility()); assertTrue(read.getCommitScope().bindings().isEmpty());
                        assertEquals(mode.equals("batch-changed") ? "BATCH_CHANGED_DURING_READ" : "COMMIT_CHECK_UNAVAILABLE",read.getCommitScope().reason());
                    } else {
                        assertEquals(read.getSnapshotScope(),read.getCommitScope().visibility().snapshot());
                        assertEquals(mode.equals("route-mismatch") ? "ROUTE_MISMATCH" : "VISIBLE",read.getCommitScope().visibility().observations().getFirst().status());
                        assertEquals(receipt.transactionId(),read.getCommitScope().visibility().observations().getFirst().receipt().transactionId());
                    }
                    var ranges = read.getCommitRangeScope(); assertNotNull(ranges);
                    if (mode.equals("batch-changed") || mode.equals("future-xid")) {
                        assertEquals(mode.equals("batch-changed") ? "BATCH_CHANGED_DURING_READ" : "COMMIT_RANGE_UNAVAILABLE",ranges.reason());
                        assertNull(ranges.visibility()); assertTrue(ranges.bindings().isEmpty());
                    } else {
                        assertEquals(read.getSnapshotScope(),ranges.visibility().snapshot());
                        assertEquals(mode.equals("route-mismatch") ? "ROUTE_MISMATCH" : "RANGE_VISIBLE",
                                ranges.visibility().observations().getFirst().status());
                        assertEquals(count,ranges.visibility().observations().getFirst().receipt().batchCount());
                        assertEquals(summary.coverage(),ranges.visibility().observations().getFirst().receipt());
                        assertEquals(mode.equals("route-mismatch") ? "ROUTE_MISMATCH"
                                : mode.equals("legacy") ? "UNPROVEN"
                                : mode.equals("truncate") || mode.equals("recreate") ? "MISMATCH" : "MATCH",
                                ranges.visibility().observations().getFirst().relationStatus());
                        assertEquals(new QualitySyncEvidenceCollector.RangeBinding(20L,1,3L),ranges.bindings().getFirst());
                    }
                }
            } finally { assertTrue(schema.matches("quality_commit_[a-f0-9]{32}")); sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
    @ParameterizedTest @ValueSource(strings={"plain","single","composite"})
    void countsAndOrganizationsShareOneSnapshotDespiteConcurrentCommit(String mode) throws Exception {
        String schema="quality_snapshot_"+UUID.randomUUID().toString().replace("-", "");
        try(var reader=open();var writer=open();var sql=writer.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                reader.setSchema(schema);writer.setSchema(schema);
                sql.execute("CREATE TABLE sample(id bigint PRIMARY KEY, org bigint, value text)");
                sql.execute("INSERT INTO sample VALUES(1,7,'ok'),(2,7,'ok')");
                var changed=new AtomicBoolean();
                var detailChanged=new AtomicBoolean();
                var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    try {
                        Object result=m.invoke(reader,a);
                        if(m.getName().equals("createStatement")) {
                            var statement=(Statement)result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->{
                                try {
                                    if(sm.getName().equals("executeQuery")) {
                                        String query=(String)sa[0];
                                        if(query.contains("GROUP BY") && (mode.equals("plain") || query.contains("\"scopeKey\""))
                                                && changed.compareAndSet(false,true))sql.execute("INSERT INTO sample VALUES(3,7,NULL)");
                                        if(query.contains("t__pk") && detailChanged.compareAndSet(false,true))
                                            sql.execute("UPDATE sample SET value='fixed' WHERE id=3");
                                    }
                                    return sm.invoke(statement,sa);
                                } catch(InvocationTargetException ex) {throw ex.getCause();}
                            });
                        }
                        return result;
                    } catch(InvocationTargetException ex) {throw ex.getCause();}
                });
                var executor=QualityRuntimeFixture.executor();
                ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
                ReflectionTestUtils.setField(executor,"auditLogMapper",mock(QualityAuditLogMapper.class));
                var issues=mock(QualityIssueDataMapper.class);ReflectionTestUtils.setField(executor,"issueDataMapper",issues);
                var route=new MasterDatabaseResolver.ConnectionInfo();
                route.setDatabaseId(4L);route.setProductType(ProductTypeEnum.POSTGRESQL);route.setSchema(schema);
                route.setConfigurationRevision("fixture");route.setUsageType(2);
                var task=QualityTaskDO.builder().id(1L).orgField("org").build();
                var object=QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName("sample").build();
                String params=switch(mode) {
                    case "single" -> "{\"column\":\"value\",\"pkColumn\":\"id\"}";
                    case "composite" -> "{\"column\":\"value\",\"pkColumns\":[\"id\",\"org\"]}";
                    default -> "{\"column\":\"value\"}";
                };
                var rule=QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION")
                        .paramSchema(params).collectIssueDetail(mode.equals("single")?1:0).build();
                QualityAuditLogDO first=QualityRuntimeFixture.executeRule(executor,10L,task,connection,route,object,rule);
                assertTrue(changed.get());assertNotNull(first);assertEquals(QualityAuditLogDO.RESULT_PASS,first.getResultType());
                assertEquals(2L,first.getTotalCount());
                var read=JsonUtils.parseObject(first.getReadEvidence(),QualityReadEvidence.class);
                assertNotNull(read.getSnapshotScope());
                assertEquals("POSTGRESQL_REPEATABLE_READ_V1",read.getSnapshotScope().protocol());
                assertEquals(2L,read.getOrgScope().getGroups().getFirst().total(),"同轮机构计数不能看到全表计数后的新提交");
                assertEquals(0L,read.getOrgScope().getGroups().getFirst().fail());
                if(!mode.equals("plain")) {
                    assertTrue(read.getKeyScope().getComplete());assertEquals(2L,read.getKeyScope().getKeyCount());
                    assertEquals(2L,read.getOrgScope().getGroups().getFirst().keyScope().getKeyCount());
                }
                assertEquals("UNPROVEN_REPLICA",read.getReadMode());
                assertTrue(reader.getAutoCommit());assertFalse(reader.isReadOnly());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED,reader.getTransactionIsolation());
                QualityAuditLogDO second=QualityRuntimeFixture.executeRule(executor,11L,task,connection,route,object,rule);
                assertNotNull(second);assertEquals(3L,second.getTotalCount());assertEquals(1L,second.getFailCount());
                assertNotEquals(read.getSnapshotScope().fingerprint(),JsonUtils.parseObject(second.getReadEvidence(),
                        QualityReadEvidence.class).getSnapshotScope().fingerprint());
                if(mode.equals("single")) {
                    assertTrue(detailChanged.get());
                    verify(issues).insert(argThat((QualityIssueDataDO issue)->QualityIssueDataDO.SCOPE_ALL.equals(issue.getStatScope())
                            && "3".equals(issue.getPkValue()) && Long.valueOf(1).equals(issue.getIssueCount())));
                }
            } finally {sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }

    @ParameterizedTest @ValueSource(strings={"rollback-failure","commit-during-read","query-failure","checkpoint-failure"})
    void invalidOrUnclosedSnapshotNeverPersistsSuccessfulEvidence(String fault) throws Exception {
        try(var raw=open();var sql=raw.createStatement()) {
            sql.execute("CREATE TEMP TABLE snapshot_failure(id bigint)");sql.execute("INSERT INTO snapshot_failure VALUES(1)");
            var injected=new AtomicBoolean();
            var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(fault.equals("rollback-failure") && m.getName().equals("rollback"))throw new SQLException("fixture");
                if(fault.equals("checkpoint-failure") && m.getName().equals("rollback") && a!=null && a.length==1)
                    throw new IllegalStateException("fixture checkpoint");
                try {
                    Object result=m.invoke(raw,a);
                    if(m.getName().equals("createStatement")) {
                        var statement=(Statement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->{
                            try {
                                if(sm.getName().equals("executeQuery") && ((String)sa[0]).contains("snapshot_failure") && injected.compareAndSet(false,true)) {
                                    if(fault.equals("commit-during-read"))raw.commit();
                                    if(fault.equals("query-failure"))throw new SQLException("fixture");
                                }
                                return sm.invoke(statement,sa);
                            } catch(InvocationTargetException ex){throw ex.getCause();}
                        });
                    }
                    return result;
                } catch(InvocationTargetException ex){throw ex.getCause();}
            });
            var mapper=mock(QualityAuditLogMapper.class);
            var executor=QualityRuntimeFixture.executor();ReflectionTestUtils.setField(executor,"auditLogMapper",mapper);
            ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
            var route=new MasterDatabaseResolver.ConnectionInfo();route.setDatabaseId(4L);
            route.setProductType(ProductTypeEnum.POSTGRESQL);route.setConfigurationRevision("fixture");route.setUsageType(1);
            var task=QualityTaskDO.builder().id(1L).orgField(fault.equals("checkpoint-failure")?"id":null).build();
            var object=QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName("snapshot_failure").build();
            var rule=QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION")
                    .paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build();
            QualityAuditLogDO audit=QualityRuntimeFixture.executeRule(executor,10L,task,connection,route,object,rule);
            assertNotNull(audit);assertEquals(QualityAuditLogDO.RESULT_ERROR,audit.getResultType());
            assertNull(audit.getReadEvidence());verify(mapper,times(1)).insert(audit);
            if(fault.endsWith("-failure") && !fault.equals("query-failure"))assertTrue(raw.isClosed());else assertTrue(raw.getAutoCommit());
        }
    }

    @Test void retryUsesFreshSnapshotAndPublishesOnlyAfterConnectionRestored() throws Exception {
        try(var raw=open();var sql=raw.createStatement()) {
            sql.execute("CREATE TEMP TABLE snapshot_retry(id bigint)");sql.execute("INSERT INTO snapshot_retry VALUES(1)");
            var failed=new AtomicBoolean();
            var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                try {
                    Object result=m.invoke(raw,a);
                    if(!m.getName().equals("createStatement"))return result;
                    var statement=(Statement)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->{
                        try {
                            if(sm.getName().equals("executeQuery") && ((String)sa[0]).contains("snapshot_retry") && failed.compareAndSet(false,true))
                                throw new SQLException("fixture first attempt");
                            return sm.invoke(statement,sa);
                        }catch(InvocationTargetException ex){throw ex.getCause();}
                    });
                }catch(InvocationTargetException ex){throw ex.getCause();}
            });
            var mapper=mock(QualityAuditLogMapper.class);
            doAnswer(call->{assertTrue(raw.getAutoCommit());assertFalse(raw.isReadOnly());return 1;}).when(mapper).insert(any(QualityAuditLogDO.class));
            var executor=QualityRuntimeFixture.executor();ReflectionTestUtils.setField(executor,"auditLogMapper",mapper);
            ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
            var route=new MasterDatabaseResolver.ConnectionInfo();route.setDatabaseId(4L);route.setUsageType(1);
            route.setProductType(ProductTypeEnum.POSTGRESQL);route.setConfigurationRevision("fixture");
            QualityAuditLogDO audit=QualityRuntimeFixture.executeRule(executor,10L,
                    QualityTaskDO.builder().id(1L).retryCount(1).build(),connection,route,
                    QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName("snapshot_retry").build(),
                    QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION")
                            .paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build());
            assertTrue(failed.get());assertNotNull(audit);assertEquals(QualityAuditLogDO.RESULT_PASS,audit.getResultType());
            assertNotNull(JsonUtils.parseObject(audit.getReadEvidence(),QualityReadEvidence.class).getSnapshotScope());
            verify(mapper,times(1)).insert(audit);
        }
    }
    private Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),System.getProperty("datahub.test.postgres.user", "dataforge_test"),"");
    }
}
