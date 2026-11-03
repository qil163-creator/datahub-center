package cn.iocoder.yudao.module.asset.service.assettag;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.*;
import cn.iocoder.yudao.module.asset.dal.mysql.categorytag.CategoryTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.service.categorytag.CategoryTagService;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 使用正式迁移的数据库保护，验证标签状态、来源、引用和关联维护范围。 */
@EnabledIfEnvironmentVariable(named="ASSET_MARKET_TEST_JDBC_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class AssetTagPostgreSqlTest {
    private String controlUrl,database,jdbcUrl;
    private Connection connection;
    private AssetTagServiceImpl service;
    private AssetCatalogService catalog;
    private AssetMaintenanceAuthorization owner;
    private String migrationText;
    private String sourceFactsMigrationText;

    @BeforeAll void setup() throws Exception {
        controlUrl=System.getenv("ASSET_MARKET_TEST_JDBC_URL");assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        database="tag_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(controlUrl,"catalog_test","");var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        String url=controlUrl.substring(0,controlUrl.lastIndexOf('/')+1)+database;
        jdbcUrl=url;
        connection=DriverManager.getConnection(url,"catalog_test","");
        for(var entry:Map.of("dh_asset_data_asset",DataAssetDO.class,"dh_asset_tag",AssetTagDO.class,
                "dh_asset_tag_rel",AssetTagRelDO.class,"dh_asset_category_tag",CategoryTagDO.class).entrySet())createEntity(entry.getKey(),entry.getValue());
        sql("CREATE TABLE dh_asset_category_data_asset(id bigint primary key,category_name varchar,deleted int default 0)");
        sql("CREATE TABLE dh_asset_import_dataset(asset_id bigint primary key,source_asset_id varchar,file_info_json jsonb default '{}'::jsonb,deleted int default 0)");
        sql("CREATE TABLE dh_market_audit_event(id bigserial primary key,event_id varchar,event_type varchar,actor_user_id bigint,dept_id bigint,object_type varchar,object_id bigint,from_status varchar,to_status varchar,reason varchar,result varchar,occurred_at timestamp,creator varchar,updater varchar)");
        sql("CREATE UNIQUE INDEX uk_test_tag_category_name ON dh_asset_tag(COALESCE(category_id,0),btrim(name)) WHERE deleted=0");
        sql("CREATE UNIQUE INDEX uk_test_tag_rel ON dh_asset_tag_rel(asset_id,tag_id) WHERE deleted=0");
        Path repo=Path.of("").toAbsolutePath();while(!Files.exists(repo.resolve("sql/postgresql/migrations/0230_asset_qdata-tags.sql")))repo=repo.getParent();
        String migration=Files.readString(repo.resolve("sql/postgresql/migrations/0230_asset_qdata-tags.sql"));
        migrationText=migration;
        sql(migration.substring(migration.indexOf("-- 数据库保护"),migration.lastIndexOf("COMMIT;")));
        sourceFactsMigrationText=Files.readString(repo.resolve("sql/postgresql/migrations/0232_asset_qdata-tag-source-presentation.sql"));
        sql(sourceFactsMigrationText.substring(sourceFactsMigrationText.indexOf("CREATE OR REPLACE FUNCTION dh_asset_tag_protect()"),sourceFactsMigrationText.lastIndexOf("COMMIT;")));
        var source=new UnpooledDataSource("org.postgresql.Driver",url,"catalog_test","");
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("tag",new SpringManagedTransactionFactory(),source));
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        var pagination=new MybatisPlusInterceptor();pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));configuration.addInterceptor(pagination);
        for(var type:List.of(AssetTagMapper.class,AssetTagRelMapper.class,CategoryTagMapper.class,DataAssetMapper.class,MarketAuditEventMapper.class))configuration.addMapper(type);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var constructor=AssetMaintenanceAuthorization.class.getDeclaredConstructor(Long.class,Long.class,boolean.class,boolean.class);constructor.setAccessible(true);owner=constructor.newInstance(9L,4L,false,false);
        catalog=mock(AssetCatalogService.class);
        var audit=new MarketAuditRecorder();ReflectionTestUtils.setField(audit,"auditEventMapper",session.getMapper(MarketAuditEventMapper.class));
        var target=new AssetTagWriter();
        var tags=session.getMapper(AssetTagMapper.class);var rels=session.getMapper(AssetTagRelMapper.class);var assets=session.getMapper(DataAssetMapper.class);var categories=session.getMapper(CategoryTagMapper.class);
        ReflectionTestUtils.setField(target,"tagMapper",tags);ReflectionTestUtils.setField(target,"relMapper",rels);ReflectionTestUtils.setField(target,"categoryMapper",categories);
        ReflectionTestUtils.setField(target,"assetMapper",assets);ReflectionTestUtils.setField(target,"catalog",catalog);ReflectionTestUtils.setField(target,"audit",audit);
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service=new AssetTagServiceImpl();ReflectionTestUtils.setField(service,"writer",proxy.getProxy());ReflectionTestUtils.setField(service,"catalog",catalog);
        ReflectionTestUtils.setField(service,"tagMapper",tags);ReflectionTestUtils.setField(service,"tagRelMapper",rels);ReflectionTestUtils.setField(service,"dataAssetMapper",assets);ReflectionTestUtils.setField(service,"categoryTagMapper",categories);
        var categoryService=mock(CategoryTagService.class);when(categoryService.getCategoryAndDescendantIds(any())).thenAnswer(inv->List.of(inv.getArgument(0)));ReflectionTestUtils.setField(service,"categoryTagService",categoryService);
        var names=mock(AssetUserNameResolver.class);when(names.resolveByTexts(any())).thenReturn(Map.of("9","维护者"));when(names.nameOf(any(),any())).thenAnswer(inv->inv.getArgument(1));ReflectionTestUtils.setField(service,"userNameResolver",names);
        var users=mock(AdminUserApi.class);when(users.getUserListByNickname(any())).thenReturn(CommonResult.success(List.of()));ReflectionTestUtils.setField(service,"userApi",users);
    }

    @BeforeEach void fixture() throws Exception {
        reset(catalog);when(catalog.prepareMaintenanceAuthorization()).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return owner;});
        doAnswer(inv->{if(!owner.canMaintain(inv.getArgument(0)))throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(DATA_ASSET_FORBIDDEN);return null;}).when(catalog).assertCanMaintain(any(),any());
        sql("TRUNCATE dh_asset_tag_rel,dh_asset_tag,dh_asset_data_asset,dh_asset_category_tag,dh_asset_category_data_asset,dh_asset_import_dataset,dh_market_audit_event RESTART IDENTITY CASCADE");
        sql("INSERT INTO dh_asset_category_tag(id,parent_id,category_name,status,deleted) VALUES(1,0,'业务',0,0),(2,0,'对象',0,0)");
        sql("INSERT INTO dh_asset_category_data_asset VALUES(1,'河道',0)");
        sql("INSERT INTO dh_asset_data_asset(id,asset_name,table_name,description,asset_type,category_id,subject_domain_name,owner_dept_id,creator,catalog_revision,create_time,deleted) VALUES(1,'河流','RIVER','河流说明',1,1,'水情',4,'9',0,'2026-01-01',0),(2,'外部资产','SECRET','不可见说明',1,1,'水情',8,'10',0,'2026-02-01',0)");
        sql("INSERT INTO dh_asset_import_dataset(asset_id,source_asset_id,deleted) VALUES(1,'49',0),(2,'50',0)");
        sql("INSERT INTO dh_asset_tag(id,name,category_id,status,revision,source_record_id,source_creator_name,creator,deleted) VALUES(1,'A',1,0,0,'3','来源昵称','migration:0230',0),(2,'B',1,1,0,NULL,NULL,'9',0),(3,'C',1,1,0,NULL,NULL,'9',0)");
    }

    @Test void enabledDefinitionRequiresDisableAndUpdateCannotSmuggleStatus() throws Exception {
        var req=request("A",1L);req.setId(1L);req.setRevision(0);
        assertThatThrownBy(()->service.updateTag(req)).hasMessageContaining(ASSET_TAG_ENABLED_IMMUTABLE.getMsg());
        service.updateTagStatus(1L,1,0);req.setRevision(1);req.setStatus(0);req.setDescription("修订说明");service.updateTag(req);
        assertThat(scalar("SELECT status||':'||revision||':'||description FROM dh_asset_tag WHERE id=1")).isEqualTo("1:2:修订说明");
        assertThatThrownBy(()->service.updateTagStatus(1L,0,0)).hasMessageContaining(ASSET_TAG_REVISION_CONFLICT.getMsg());
    }

    @Test void duplicateNamesAcrossCategoriesAreIndependentButSameCategoryIsUnique() {
        Long id=service.createTag(request(" B ",2L));assertThat(service.getTag(id).getName()).isEqualTo("B");
        assertThatThrownBy(()->service.createTag(request("B",1L))).hasMessageContaining(ASSET_TAG_NAME_DUPLICATE.getMsg());
    }

    @Test void pageCountsSortAndLinkedAssetsShareMaintenanceScopeWithoutRestrictingInternalEnrichment() throws Exception {
        sql("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,1,0,0),(12,2,1,0,0)");
        var req=new AssetTagPageReqVO();req.setSortField("assetCount");req.setSortOrder("desc");req.setPageSize(1);req.setCreatorName("来源");
        var page=service.getTagPage(req);assertThat(page.getTotal()).isEqualTo(1);assertThat(page.getList().getFirst().getAssetCount()).isEqualTo(1);
        var linked=new AssetTagLinkedPageReqVO();linked.setId(1L);linked.setPageSize(1);linked.setSortField("assetId");
        var assets=service.linkedAssets(linked);assertThat(assets.getTotal()).isEqualTo(1);assertThat(assets.getList().getFirst().getSourceRecordId()).isEqualTo("49");
        assertThat(assets.getList().getFirst().getCategoryName()).isEqualTo("河道");
        assertThatThrownBy(()->service.getTagsByAssetId(2L)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        assertThat(service.getTagsByAssetIds(List.of(2L)).get(2L)).hasSize(1);
    }

    @Test void bindAndUnbindEnforceObjectScopeAndPreserveUnchangedRelations() throws Exception {
        var req=new AssetTagBindReqVO();req.setAssetId(2L);req.setCatalogRevision(0L);req.setTagIds(List.of(1L));
        assertThatThrownBy(()->service.bindTags(req)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        req.setAssetId(1L);service.bindTags(req);String id=scalar("SELECT id FROM dh_asset_tag_rel WHERE asset_id=1 AND deleted=0");
        assertThatThrownBy(()->service.bindTags(req)).hasMessageContaining(ASSET_TAG_REVISION_CONFLICT.getMsg());
        req.setCatalogRevision(1L);service.bindTags(req);assertThat(scalar("SELECT id FROM dh_asset_tag_rel WHERE asset_id=1 AND deleted=0")).isEqualTo(id);
        assertThatThrownBy(()->service.unbind(Long.valueOf(id),4)).hasMessageContaining(ASSET_TAG_REVISION_CONFLICT.getMsg());
        service.unbind(Long.valueOf(id),0);assertThat(scalar("SELECT deleted||':'||revision FROM dh_asset_tag_rel WHERE id="+id)).isEqualTo("1:1");
        assertThat(scalar("SELECT catalog_revision FROM dh_asset_data_asset WHERE id=1")).isEqualTo("2");
    }

    @Test void wholeBatchRejectsEnabledStaleOrReferencedRecordsBeforeAnyDeletion() throws Exception {
        assertThatThrownBy(()->service.deleteTags(List.of(item(2L,0),item(1L,0)))).hasMessageContaining(ASSET_TAG_ENABLED_IMMUTABLE.getMsg());
        assertThatThrownBy(()->service.deleteTags(List.of(item(2L,0),item(3L,5)))).hasMessageContaining(ASSET_TAG_REVISION_CONFLICT.getMsg());
        assertThat(scalar("SELECT deleted FROM dh_asset_tag WHERE id=2")).isEqualTo("0");
        sql("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,1,0,0)");service.updateTagStatus(1L,1,0);
        assertThatThrownBy(()->service.deleteTags(List.of(item(2L,0),item(1L,1)))).hasMessageContaining(ASSET_TAG_IN_USE.getMsg());
        service.deleteTags(List.of(item(2L,0),item(3L,0)));assertThat(scalar("SELECT count(*) FROM dh_asset_tag WHERE deleted=1")).isEqualTo("2");
    }

    @Test void databaseRejectsDirectEnabledEditReferencedDeleteAndDisabledBinding() throws Exception {
        assertThatThrownBy(()->sql("UPDATE dh_asset_tag SET description='绕过' WHERE id=1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("UPDATE dh_asset_tag SET deleted=1 WHERE id=1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->sql("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,2,0,0)")).isInstanceOf(SQLException.class);
        sql("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,1,0,0)");service.updateTagStatus(1L,1,0);
        assertThatThrownBy(()->sql("UPDATE dh_asset_tag SET deleted=1 WHERE id=1")).isInstanceOf(SQLException.class);
    }

    @Test void auditFailureRollsBackBindAndCatalogRevision() throws Exception {
        sql("ALTER TABLE dh_market_audit_event ADD CONSTRAINT reject_bind CHECK(event_type<>'ASSET_TAG_BIND')");
        var req=new AssetTagBindReqVO();req.setAssetId(1L);req.setCatalogRevision(0L);req.setTagIds(List.of(1L));
        try{assertThatThrownBy(()->service.bindTags(req)).isInstanceOf(RuntimeException.class);
            assertThat(scalar("SELECT count(*) FROM dh_asset_tag_rel")).isEqualTo("0");assertThat(scalar("SELECT catalog_revision FROM dh_asset_data_asset WHERE id=1")).isEqualTo("0");}
        finally{sql("ALTER TABLE dh_market_audit_event DROP CONSTRAINT reject_bind");}
    }

    @Test void malformedBindingsAndConcurrentCatalogRevisionCannotOverwriteSelection() throws Exception {
        var req=new AssetTagBindReqVO();req.setAssetId(1L);req.setCatalogRevision(0L);req.setTagIds(Arrays.asList((Long)null));
        assertThatThrownBy(()->service.bindTags(req)).hasMessageContaining(ASSET_TAG_INPUT_INVALID.getMsg());
        req.setTagIds(List.of(1L));var start=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++)tasks.add(workers.submit(()->{start.await();try{service.bindTags(req);return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){assertThat(e.getCode()).isEqualTo(ASSET_TAG_REVISION_CONFLICT.getCode());return false;}}));
            start.countDown();int wins=0;for(var task:tasks)if(task.get(10,java.util.concurrent.TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);
        }
        assertThat(scalar("SELECT count(*) FROM dh_asset_tag_rel WHERE deleted=0")).isEqualTo("1");
    }

    @Test void directBindingWaitsForAssetBeforeTakingTagLock() throws Exception {
        try(var first=DriverManager.getConnection(jdbcUrl,"catalog_test","");var second=DriverManager.getConnection(jdbcUrl,"catalog_test","");
            var workers=java.util.concurrent.Executors.newSingleThreadExecutor()){
            first.setAutoCommit(false);second.setAutoCommit(false);
            try(var s=first.createStatement()){s.execute("SELECT id FROM dh_asset_data_asset WHERE id=1 FOR UPDATE");}
            int secondPid;try(var s=second.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();secondPid=r.getInt(1);}
            var pending=workers.submit(()->{try(var s=second.createStatement()){s.execute("SET LOCAL statement_timeout='5s'");s.execute("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,1,0,0)");second.commit();return true;}});
            waitForDatabaseLock(secondPid);
            try(var s=first.createStatement()){s.execute("SELECT id FROM dh_asset_tag WHERE id=1 FOR UPDATE NOWAIT");}
            first.rollback();assertThat(pending.get(8,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void categoryDeletionWaitsAndThenRejectsNewlyCommittedTag() throws Exception {
        try(var first=DriverManager.getConnection(jdbcUrl,"catalog_test","");var second=DriverManager.getConnection(jdbcUrl,"catalog_test","");
            var workers=java.util.concurrent.Executors.newSingleThreadExecutor()){
            first.setAutoCommit(false);second.setAutoCommit(false);
            try(var s=first.createStatement()){s.execute("SELECT id FROM dh_asset_category_tag WHERE id=2 FOR SHARE");}
            int secondPid;try(var s=second.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();secondPid=r.getInt(1);}
            var pending=workers.submit(()->{try(var s=second.createStatement()){s.execute("SET LOCAL statement_timeout='5s'");s.execute("UPDATE dh_asset_category_tag SET deleted=1 WHERE id=2");second.commit();return true;}
                catch(SQLException e){second.rollback();return false;}});
            waitForDatabaseLock(secondPid);
            try(var s=first.createStatement()){s.execute("INSERT INTO dh_asset_tag(id,name,category_id,status,revision,deleted) VALUES(4,'新增',2,0,0,0)");}
            first.commit();assertThat(pending.get(8,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
        assertThat(scalar("SELECT deleted FROM dh_asset_category_tag WHERE id=2")).isEqualTo("0");
    }

    @Test void importedListKeepsSourceOrdinalAndNumericNumberWithLocalAuditDatesUnchanged() throws Exception {
        sql("TRUNCATE dh_asset_tag_rel,dh_asset_tag,dh_asset_category_tag CASCADE");
        connection.setAutoCommit(false);
        try {
            int start=migrationText.indexOf("CREATE TEMP TABLE qdata_tag_category_seed(");
            int end=migrationText.indexOf("CREATE TEMP TABLE qdata_tag_rel_seed",start);
            sql(migrationText.substring(start,end));
            int factsStart=sourceFactsMigrationText.indexOf("CREATE TEMP TABLE qdata_tag_display_seed");
            int factsEnd=sourceFactsMigrationText.indexOf("-- 来源次序和时间只补缺失事实",factsStart);
            sql(sourceFactsMigrationText.substring(factsStart,factsEnd));
            sql("""
                INSERT INTO dh_asset_category_tag(id,parent_id,category_name,category_code,sort,status,deleted)
                  SELECT source_id+300000,CASE WHEN parent_source_id=0 THEN 0 ELSE parent_source_id+300000 END,name,code,sort,status,0 FROM qdata_tag_category_seed;
                INSERT INTO dh_asset_tag(id,name,category_id,status,revision,source_record_id,source_sort_order,
                  source_create_time,source_update_time,source_creator_name,source_updater_name,creator,updater,create_time,update_time,deleted)
                  SELECT CASE WHEN s.source_id<=26 THEN 100000-s.source_id ELSE 200000+s.source_id END,s.name,c.id,s.status,0,
                    s.source_id::text,f.source_sort_order,f.create_time,f.update_time,s.creator_name,s.updater_name,'migration:0230','migration:0230',
                    CASE WHEN s.source_id<=26 THEN '2026-10-23 12:00:00'::timestamp ELSE s.create_time END,
                    '2026-10-23 12:00:00'::timestamp,0
                  FROM qdata_tag_seed s JOIN dh_asset_category_tag c ON c.category_code=s.category_code
                    JOIN qdata_tag_display_seed f ON f.source_id=s.source_id::text
                """);
            connection.commit();
        } finally { connection.rollback();connection.setAutoCommit(true); }
        var req=new AssetTagPageReqVO();req.setPageSize(10);
        assertThat(service.getTagPage(req).getList()).extracting(AssetTagRespVO::getSourceRecordId)
                .containsExactly("2","3","4","5","6","7","8","9","10","11");
        req.setPageSize(200);var rows=service.getTagPage(req).getList();assertThat(rows).hasSize(160);
        assertThat(rows.subList(153,160)).extracting(AssetTagRespVO::getSourceRecordId)
                .containsExactly("1","13","49","52","69","98","105");
        assertThat(rows.getFirst().getCreateTime()).isEqualTo(java.time.LocalDateTime.of(2026,7,29,19,9,39));
        assertThat(rows.getFirst().getUpdateTime()).isEqualTo(java.time.LocalDateTime.of(2026,10,22,18,15,15));
        assertThat(scalar("SELECT update_time FROM dh_asset_tag WHERE source_record_id='2'")).startsWith("2026-10-23");
        assertThat(scalar("SELECT create_time FROM dh_asset_tag WHERE source_record_id='2'")).startsWith("2026-10-23");
        req.setSortField("id");req.setSortOrder("asc");req.setPageSize(10);
        assertThat(service.getTagPage(req).getList()).extracting(AssetTagRespVO::getSourceRecordId)
                .containsExactly("1","2","3","4","5","6","7","8","9","10");
        sql("INSERT INTO dh_asset_tag(id,name,category_id,status,revision,creator,updater,create_time,deleted) VALUES(7777777,'本地新增',300010,0,0,'9','9','2027-01-01',0)");
        req.setSortField("createTime");req.setSortOrder("desc");assertThat(service.getTagPage(req).getList().getFirst().getName()).isEqualTo("本地新增");
        Long importedId=Long.valueOf(scalar("SELECT id FROM dh_asset_tag WHERE source_record_id='2'"));
        service.updateTagStatus(importedId,1,0);var edited=service.getTag(importedId);
        assertThat(edited.getSourceUpdateTime()).isNull();assertThat(edited.getUpdaterName()).isEqualTo("9");
        assertThat(edited.getUpdateTime()).isEqualTo(java.time.LocalDateTime.parse(scalar("SELECT update_time FROM dh_asset_tag WHERE id="+importedId).replace(' ','T')));
        assertThat(edited.getCreateTime()).isEqualTo(java.time.LocalDateTime.of(2026,7,29,19,9,39));
    }

    @Test void linkedSourceNumberCreatorAndTimeAreLosslessPresentationFactsWithSafeFallback() throws Exception {
        sql("INSERT INTO dh_asset_data_asset(id,asset_name,table_name,asset_type,category_id,owner_dept_id,creator,catalog_revision,create_time,deleted) VALUES(3,'原编号二','RIVER2',1,1,4,'9',0,'2026-03-01',0)");
        sql("INSERT INTO dh_asset_import_dataset(asset_id,source_asset_id,file_info_json,deleted) VALUES(3,'2','{\"sourceCreatorName\":\"来源乙\",\"sourceCreateTime\":\"2025-12-01 00:00:00\"}',0)");
        sql("UPDATE dh_asset_import_dataset SET file_info_json='{\"sourceCreatorName\":\"来源甲\",\"sourceCreateTime\":\"2026-01-03 10:00:00\"}' WHERE asset_id=1");
        sql("INSERT INTO dh_asset_tag_rel(id,asset_id,tag_id,revision,deleted) VALUES(11,1,1,0,0),(12,2,1,0,0),(13,3,1,0,0)");
        var req=new AssetTagLinkedPageReqVO();req.setId(1L);req.setSortField("assetId");req.setSortOrder("asc");
        var rows=service.linkedAssets(req).getList();assertThat(rows).extracting(AssetTagLinkedRespVO::getAssetId).containsExactly(3L,1L);
        assertThat(rows).extracting(AssetTagLinkedRespVO::getSourceRecordId).containsExactly("2","49");
        assertThat(rows.getLast().getCreatorName()).isEqualTo("来源甲");assertThat(rows.getLast().getCreator()).isEqualTo("9");
        assertThat(rows.getLast().getCreateTime()).isEqualTo(java.time.LocalDateTime.of(2026,1,3,10,0));
        assertThat(scalar("SELECT create_time FROM dh_asset_data_asset WHERE id=1")).startsWith("2026-01-01");
        req.setSortOrder("desc");assertThat(service.linkedAssets(req).getList()).extracting(AssetTagLinkedRespVO::getAssetId).containsExactly(1L,3L);
        req.setSortField("createTime");assertThat(service.linkedAssets(req).getList()).extracting(AssetTagLinkedRespVO::getAssetId).containsExactly(1L,3L);
        for(String invalid:List.of("2026-02-31 00:00:00","now")){
            try(var s=connection.prepareStatement("UPDATE dh_asset_import_dataset SET file_info_json=jsonb_build_object('sourceCreatorName',123,'sourceCreateTime',CAST(? AS text)) WHERE asset_id=3")){s.setString(1,invalid);s.executeUpdate();}
            var fallback=service.linkedAssets(req).getList().getFirst();assertThat(fallback.getAssetId()).isEqualTo(3L);assertThat(fallback.getCreatorName()).isEqualTo("9");
            assertThat(fallback.getCreateTime()).isEqualTo(java.time.LocalDateTime.of(2026,3,1,0,0));
        }
        sql("UPDATE dh_asset_import_dataset SET file_info_json='{\"sourceCreatorName\":\"   \",\"sourceCreateTime\":123}',source_asset_id='9007199254740993' WHERE asset_id=3");
        req.setSortField("assetId");req.setSortOrder("asc");assertThat(service.linkedAssets(req).getList()).extracting(AssetTagLinkedRespVO::getSourceRecordId).containsExactly("49","9007199254740993");
        assertThat(service.linkedAssets(req).getList().getLast().getCreatorName()).isEqualTo("9");
    }

    private void waitForDatabaseLock(int pid)throws Exception{
        for(int i=0;i<100;i++){
            if("Lock".equals(scalar("SELECT coalesce(wait_event_type,'') FROM pg_stat_activity WHERE pid="+pid)))return;
            Thread.sleep(20);
        }
        throw new AssertionError("并发连接未按预期等待数据库行锁");
    }

    @AfterAll void cleanup() throws Exception {if(connection!=null)connection.close();if(database!=null)try(var c=DriverManager.getConnection(controlUrl,"catalog_test","");var s=c.createStatement()){s.execute("DROP DATABASE "+database+" WITH(FORCE)");}}
    private AssetTagSaveReqVO request(String name,Long category){var req=new AssetTagSaveReqVO();req.setName(name);req.setCategoryId(category);return req;}
    private AssetTagDeleteListReqVO.Item item(Long id,int revision){var i=new AssetTagDeleteListReqVO.Item();i.setId(id);i.setRevision(revision);return i;}
    private void sql(String text)throws SQLException{try(var s=connection.createStatement()){s.execute(text);}}
    private String scalar(String text)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    private void createEntity(String table,Class<?> type)throws SQLException{
        var columns=new LinkedHashMap<String,String>();for(var current=type;current!=Object.class;current=current.getSuperclass())for(var f:current.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers())||f.getName().equals("transMap"))continue;String name=StrUtil.toUnderlineCase(f.getName());Class<?> t=f.getType();
            String db=t==Long.class?"bigint":t==Integer.class||t==Boolean.class?"integer":t==java.time.LocalDateTime.class?"timestamp":t==java.math.BigDecimal.class?"numeric":"varchar";
            columns.put(name,name+" "+db+(name.equals("id")?" PRIMARY KEY":name.equals("deleted")||name.equals("revision")?" DEFAULT 0":""));}
        sql("CREATE TABLE "+table+"("+String.join(",",columns.values())+")");
    }
}
