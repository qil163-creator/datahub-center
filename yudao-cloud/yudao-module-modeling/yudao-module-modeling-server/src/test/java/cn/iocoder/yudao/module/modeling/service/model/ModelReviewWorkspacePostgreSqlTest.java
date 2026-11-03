package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL：0120 之前的工作台历史阶段、0112/0113 重放、冻结/归档约束及历史目标隔离。 */
class ModelReviewWorkspacePostgreSqlTest {
    private static Connection db;
    private static String schema;
    private static Path root;
    private static final AtomicLong IDS=new AtomicLong(910000);
    @BeforeAll static void initialize() throws Exception {
        String url=System.getenv("MODELING_TEST_PG_URL");
        Assumptions.assumeTrue(url!=null && url.startsWith("jdbc:postgresql://127.0.0.1:"),"需配置本次隔离的 PostgreSQL，不使用 H2 替代");
        db=DriverManager.getConnection(url,"modeling_test","");
        schema="modeling_review_"+UUID.randomUUID().toString().replace("-","");
        root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("sql/postgresql/migrations")))root=root.getParent();
        assertNotNull(root);
        // 0113 不会重新启用停用权限，因此历史重放必须使用 0120 生效前的完整基线前缀。
        // 当前完整新库基线和 0120 的最终权限状态由 ModelSingleTargetMigrationPostgreSqlTest 验证。
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int singleTargetStart=baseline.indexOf("-- Migration: 0120 ");
        assertTrue(singleTargetStart>0,"完整基线必须包含单目标迁移边界");
        scriptSql(baseline.substring(0,singleTargetStart));
        script(root.resolve("sql/postgresql/migrations/0112_modeling_review-workspace.sql"));
        script(root.resolve("sql/postgresql/migrations/0113_modeling_review-workspace-permissions.sql"));
        execute("SET search_path TO \""+schema+"\",public");
    }
    @AfterAll static void cleanup() throws Exception {
        if(db==null)return;
        try { if(schema!=null && schema.matches("modeling_review_[a-f0-9]{32}"))execute("DROP SCHEMA \""+schema+"\" CASCADE"); }
        finally { db.close(); }
    }
    @Test void migrationsReplayWithoutDuplicatingMenuPermissions() throws Exception {
        script(root.resolve("sql/postgresql/migrations/0112_modeling_review-workspace.sql"));
        script(root.resolve("sql/postgresql/migrations/0113_modeling_review-workspace-permissions.sql"));
        assertEquals(4,count("SELECT count(*) FROM system_menu WHERE permission LIKE 'modeling:workspace:%' AND deleted=0"));
    }
    @Test void legacyModelingFixtureUpgradesThroughWorkspaceAndBaselineMatchesMigration() throws Exception {
        String namespace="modeling_fixture_"+UUID.randomUUID().toString().replace("-","");
        try {
            ModelingMigrationTestSupport.resetBaselineTables(db,namespace);
            ModelingMigrationTestSupport.migrate(db,namespace);
            ModelingMigrationTestSupport.migrate(db,namespace);
            assertEquals(1,count("SELECT count(*) FROM information_schema.columns WHERE table_schema='"+namespace
                    +"' AND table_name='dh_modeling_deploy_record' AND column_name='baseline_status'"));
            assertEquals(0,count("SELECT count(*) FROM \""+namespace+"\".dh_modeling_design_report"));
            String migration=Files.readString(root.resolve(ModelingMigrationTestSupport.REVIEW_WORKSPACE_MIGRATION))
                    .replace("\r\n","\n").strip();
            assertEquals(migration,ModelingMigrationTestSupport.baselineBlock("-- Migration: 0112\n").strip());
        } finally {
            execute("DROP SCHEMA IF EXISTS \""+namespace+"\" CASCADE");
        }
    }
    @Test void conceptHeaderAndCanvasCannotChangeWhileApprovalIsActive() throws Exception {
        long model=concept();long entity=next();
        execute("INSERT INTO dh_modeling_concept_entity(id,tenant_id,model_id,name) VALUES("+entity+",7,"+model+",'自然人')");
        approval("CONCEPT",model,"RUNNING",7);
        rejects("UPDATE dh_modeling_concept_model SET name='居民个人' WHERE id="+model,"23514");
        rejects("UPDATE dh_modeling_concept_entity SET name='居民个人' WHERE id="+entity,"23514");
        rejects("DELETE FROM dh_modeling_concept_entity WHERE id="+entity,"23514");
    }
    @Test void cancellationUnfreezesContentAndAllowsRealChildDeletion() throws Exception {
        long model=concept();long entity=next();long approval=approval("CONCEPT",model,"RUNNING",7);
        execute("UPDATE dh_modeling_approval SET status='CANCELLED',cancel_requested=true,cancel_reason='需修订业务标识' WHERE id="+approval);
        execute("INSERT INTO dh_modeling_concept_entity(id,tenant_id,model_id,name) VALUES("+entity+",7,"+model+",'自然人')");
        execute("DELETE FROM dh_modeling_concept_entity WHERE id="+entity);
        assertEquals(0,count("SELECT count(*) FROM dh_modeling_concept_entity WHERE id="+entity));
    }
    @Test void physicalOperationalStatusCanChangeButFrozenDesignCannot() throws Exception {
        long model=physical();approval("PHYSICAL",model,"START_PENDING",7);
        execute("UPDATE dh_modeling_physical_model SET deploy_status='SUCCEEDED',deploy_time=CURRENT_TIMESTAMP WHERE id="+model);
        rejects("UPDATE dh_modeling_physical_model SET table_name='dwd_resident_changed' WHERE id="+model,"23514");
    }
    @Test void publishedConsumptionAndModelMutationShareOneTransaction() throws Exception {
        long model=concept();long approval=approval("CONCEPT",model,"APPROVED",7);
        db.setAutoCommit(false);
        try {
            execute("UPDATE dh_modeling_approval SET status='CONSUMED' WHERE id="+approval+" AND status='APPROVED'");
            execute("UPDATE dh_modeling_concept_model SET name='人口基础模型已发布' WHERE id="+model);
            db.rollback();
        }finally{db.setAutoCommit(true);}
        assertEquals("APPROVED",scalar("SELECT status FROM dh_modeling_approval WHERE id="+approval));
        assertEquals("人口基础模型",scalar("SELECT name FROM dh_modeling_concept_model WHERE id="+model));
        rejects("UPDATE dh_modeling_concept_model SET name='违规变更' WHERE id="+model,"23514");
    }
    @Test void immutableApprovalEvidenceCannotBeReplacedOrMovedToAnotherTenant() throws Exception {
        long model=concept();long id=approval("CONCEPT",model,"RUNNING",7);
        rejects("UPDATE dh_modeling_approval SET snapshot='[\"另一份候选\"]' WHERE id="+id,"23514");
        rejects("UPDATE dh_modeling_approval SET tenant_id=8 WHERE id="+id,"23514");
        rejects("UPDATE dh_modeling_approval SET reason='替换原始审批原因' WHERE id="+id,"23514");
        execute("UPDATE dh_modeling_approval SET attempts=1,last_error='连接待对账' WHERE id="+id);
    }
    @Test void activityUniquenessAndRetryLeaseUsePersistentCas() throws Exception {
        long model=concept();long id=approval("CONCEPT",model,"START_PENDING",7);
        rejects(approvalSql("CONCEPT",model,"RUNNING",7,next()),"23505");
        assertEquals(1,update("UPDATE dh_modeling_approval SET lease_token='first',lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes' WHERE id="+id+" AND lease_until IS NULL"));
        assertEquals(0,update("UPDATE dh_modeling_approval SET lease_token='second' WHERE id="+id+" AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)"));
        execute("UPDATE dh_modeling_approval SET status='CANCEL_PENDING',cancel_requested=true,cancel_reason='撤回' WHERE id="+id);
        assertEquals(0,update("UPDATE dh_modeling_approval SET status='APPROVED' WHERE id="+id+" AND status='START_PENDING' AND lease_token='first'"));
    }
    @Test void designArchivePreservesFullExemptionEvidence() throws Exception {
        long id=next();
        execute("INSERT INTO dh_modeling_design_report(id,tenant_id,kind,model_id,digest,passed,report) VALUES("+id+",7,'LOGIC',1,'"+"a".repeat(64)+"',false,'{\"standardGate\":{\"overrideBy\":42,\"overrideReason\":\"历史编码需保留\",\"evidenceHash\":\"evidence\",\"reportHash\":\"report\"}}')");
        assertEquals("历史编码需保留",scalar("SELECT report #>> '{standardGate,overrideReason}' FROM dh_modeling_design_report WHERE id="+id));
        rejects("UPDATE dh_modeling_design_report SET report='{}' WHERE id="+id,"23514");
        rejects("DELETE FROM dh_modeling_design_report WHERE id="+id,"23514");
    }
    @Test void duplicateDeliveryCannotCreateTwoTodosForOneRecipient() throws Exception {
        long event=next();long recipient=42;
        execute("INSERT INTO dh_modeling_inbox(tenant_id,event_id,recipient_user_id,business_object_id,version_no,model_id,entity_id,summary,status,delivered_at) VALUES(7,"+event+","+recipient+",1,2,1,1,'自然人对象身份证号定义修订','DELIVERED',CURRENT_TIMESTAMP)");
        rejects("INSERT INTO dh_modeling_inbox(tenant_id,event_id,recipient_user_id,business_object_id,version_no,model_id,entity_id,summary,status,delivered_at) VALUES(7,"+event+","+recipient+",1,2,1,1,'重复投递','DELIVERED',CURRENT_TIMESTAMP)","23505");
        rejects("UPDATE dh_modeling_inbox SET status='RESOLVED' WHERE tenant_id=7 AND event_id="+event,"23514");
        execute("UPDATE dh_modeling_inbox SET status='RESOLVED',decision='KEEP_CURRENT',reason='旧接口尚未升级，下期适配',resolved_at=CURRENT_TIMESTAMP WHERE tenant_id=7 AND event_id="+event);
    }
    @Test void baselineLookupKeepsEnvironmentAndTenantIndependent() throws Exception {
        long model=physical();
        execute("INSERT INTO dh_modeling_schema_baseline(tenant_id,model_id,version_no,database_id,table_name) VALUES(7,"+model+",1,101,'dwd_resident'),(7,"+model+",2,202,'dwd_resident'),(8,"+model+",9,101,'dwd_resident')");
        assertEquals("1",scalar("SELECT version_no FROM dh_modeling_schema_baseline WHERE tenant_id=7 AND model_id="+model+" AND database_id=101 AND table_name='dwd_resident' ORDER BY id DESC LIMIT 1"));
        assertEquals("2",scalar("SELECT version_no FROM dh_modeling_schema_baseline WHERE tenant_id=7 AND model_id="+model+" AND database_id=202 AND table_name='dwd_resident' ORDER BY id DESC LIMIT 1"));
    }
    private static long concept() throws Exception {long id=next();execute("INSERT INTO dh_modeling_concept_model(id,tenant_id,name) VALUES("+id+",7,'人口基础模型')");return id;}
    private static long physical() throws Exception {long id=next();execute("INSERT INTO dh_modeling_physical_model(id,tenant_id,database_id,name,table_name) VALUES("+id+",7,101,'人口基础明细','dwd_resident')");return id;}
    private static long approval(String kind,long model,String status,long tenant)throws Exception{long id=next();execute(approvalSql(kind,model,status,tenant,id));return id;}
    private static String approvalSql(String kind,long model,String status,long tenant,long id){return "INSERT INTO dh_modeling_approval(id,tenant_id,kind,model_id,version_no,digest,snapshot,business_key,process_definition_key,applicant_id,status,reason) VALUES("+id+","+tenant+",'"+kind+"',"+model+",1,'"+"a".repeat(64)+"','[]','test-key-"+id+"','model_publish',42,'"+status+"','人口基础模型定义评审')";}
    private static long next(){return IDS.incrementAndGet();}
    private static void script(Path path)throws Exception{scriptSql(Files.readString(path));}
    private static void scriptSql(String sql)throws Exception{execute(sql.replace("\uFEFF","").replace("\"datahub-center\"","\""+schema+"\"").replace("'datahub-center'","'"+schema+"'"));}
    private static void execute(String sql)throws SQLException{try(var s=db.createStatement()){s.execute(sql);}}
    private static int update(String sql)throws SQLException{try(var s=db.createStatement()){return s.executeUpdate(sql);}}
    private static String scalar(String sql)throws SQLException{try(var s=db.createStatement();var rs=s.executeQuery(sql)){assertTrue(rs.next());return rs.getString(1);}}
    private static long count(String sql)throws SQLException{return Long.parseLong(scalar(sql));}
    private static void rejects(String sql,String state){var failure=assertThrows(SQLException.class,()->execute(sql));assertEquals(state,failure.getSQLState());}
}
