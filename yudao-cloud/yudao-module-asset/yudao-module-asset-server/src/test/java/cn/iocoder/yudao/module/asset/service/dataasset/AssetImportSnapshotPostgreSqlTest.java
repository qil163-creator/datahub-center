package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
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

import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 真实 PostgreSQL 验证 JSONB 精确读写、修订 CAS、对象范围及原子修改历史。 */
@EnabledIfEnvironmentVariable(named="ASSET_MARKET_TEST_JDBC_URL", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class AssetImportSnapshotPostgreSqlTest {
    private Connection connection;
    private String controlUrl;
    private String database;
    private AssetImportSnapshotService service;
    private MarketAuditRecorder audit;
    private SqlSessionTemplate session;
    private final cn.iocoder.yudao.module.system.api.user.AdminUserApi users =
            mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class);
    private final AssetMaintenanceAuthorization owner = new AssetMaintenanceAuthorization(9L,4L,false,false);
    @BeforeAll
    void prepare() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        database = "asset_import_" + UUID.randomUUID().toString().replace("-", "");
        try (var control=DriverManager.getConnection(controlUrl,"catalog_test",""); var statement=control.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0,controlUrl.lastIndexOf('/')+1)+database;
        connection=DriverManager.getConnection(url,"catalog_test","");
        sql("CREATE TABLE dh_asset_data_asset(id bigint primary key,external_code varchar,owner_dept_id bigint,creator varchar,deleted int default 0)");
        String base = ",creator varchar default '',updater varchar default '',create_time timestamp default CURRENT_TIMESTAMP,update_time timestamp default CURRENT_TIMESTAMP,deleted int default 0";
        sql("CREATE TABLE dh_asset_import_dataset(asset_id bigint primary key,source_asset_id varchar,source_name varchar,source_type varchar,source_data_count bigint,imported_row_count bigint,complete boolean,columns_json jsonb,file_info_json jsonb,quality_json jsonb,lineage_json jsonb,revision int default 0"+base+")");
        sql("CREATE TABLE dh_asset_import_row(id bigint primary key,asset_id bigint,ordinal int,row_json jsonb,revision int default 0"+base+")");
        sql("CREATE SEQUENCE dh_asset_import_row_log_seq");
        sql("CREATE TABLE dh_asset_import_row_log(id bigint primary key default nextval('dh_asset_import_row_log_seq'),asset_id bigint,row_id bigint,revision int,before_json jsonb,after_json jsonb"+base+")");
        var source = new UnpooledDataSource("org.postgresql.Driver",url,"catalog_test","");
        var configuration=new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("asset-import",new SpringManagedTransactionFactory(),source));
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        for (var mapper:List.of(AssetImportDatasetMapper.class,AssetImportRowMapper.class,AssetImportRowLogMapper.class,DataAssetMapper.class))
            configuration.addMapper(mapper);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var target=new AssetImportSnapshotService();
        ReflectionTestUtils.setField(target,"datasetMapper",session.getMapper(AssetImportDatasetMapper.class));
        ReflectionTestUtils.setField(target,"rowMapper",session.getMapper(AssetImportRowMapper.class));
        ReflectionTestUtils.setField(target,"logMapper",session.getMapper(AssetImportRowLogMapper.class));
        ReflectionTestUtils.setField(target,"assetMapper",session.getMapper(DataAssetMapper.class));
        audit=mock(MarketAuditRecorder.class);
        ReflectionTestUtils.setField(target,"audit",audit);
        var names=mock(AssetUserNameResolver.class);
        when(names.resolveByTexts(any())).thenReturn(Map.of("9","本地维护者"));
        when(names.nameOf(any(),eq("9"))).thenReturn("本地维护者");
        ReflectionTestUtils.setField(target,"userNames",names);
        ReflectionTestUtils.setField(target,"userApi",users);
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service=(AssetImportSnapshotService)proxy.getProxy();
    }

    @BeforeEach
    void fixture() throws Exception {
        reset(audit);
        sql("TRUNCATE dh_asset_data_asset,dh_asset_import_dataset,dh_asset_import_row,dh_asset_import_row_log RESTART IDENTITY");
        sql("INSERT INTO dh_asset_data_asset VALUES(1,'qdata:asset:49',4,'9',0),(2,'qdata:asset:50',8,'10',0)");
        sql("""
            INSERT INTO dh_asset_import_dataset(asset_id,source_asset_id,columns_json,file_info_json,source_data_count,imported_row_count,complete) VALUES
            (1,'49','[{"id":1,"columnName":"ID","pkFlag":1,"dataType":"bigint","nullableFlag":0},
            {"id":2,"columnName":"VALUE","dataType":"decimal","dataScale":20,"nullableFlag":0},
            {"id":3,"columnName":"NAME","dataType":"varchar","dataLength":100}]','{}',2,2,true)
            """);
        sql("""
            INSERT INTO dh_asset_import_row VALUES
            (11,1,1,'{"ID":"9007199254740993","VALUE":"12345678901234567890.12345678901234567890","NAME":"河道"}',0,'','','2026-01-01','2026-01-01',0),
            (12,1,2,'{"ID":"9007199254740994","VALUE":"2.00","NAME":null}',0,'','','2026-01-01','2026-01-01',0)
            """);
    }

    @Test
    void previewPagesAndExactDecimalWithoutDoubleConversion() {
        var req=previewRequest();
        var result=service.preview(asset(),req);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRowIds()).containsExactly("11");
        assertThat(result.getRows().getFirst().get("VALUE")).isEqualTo("12345678901234567890.12345678901234567890");
        assertThat(JsonUtils.toJsonString(result)).contains('"' + "9007199254740993" + '"');
        req.setPageNo(2);
        assertThat(service.preview(asset(),req).getRowIds()).containsExactly("12");
    }

    @Test
    void filtersAreBoundAndNumericComparisonsUseExactNumeric() {
        var req=previewRequest(); var filter=new DataAssetPreviewReqVO.Filter();
        filter.setColumn("VALUE");filter.setOperator("gt");filter.setValue("12345678901234567890.12345678901234567889");
        req.setFilters(List.of(filter));
        assertThat(service.preview(asset(),req).getTotal()).isEqualTo(1);
        filter.setColumn("NAME");filter.setOperator("eq");filter.setValue("' OR TRUE --");
        assertThat(service.preview(asset(),req).getTotal()).isZero();
        filter.setColumn("NAME') OR TRUE--");
        assertThatThrownBy(()->service.preview(asset(),req)).hasMessageContaining(DATA_ASSET_IMPORT_INPUT_INVALID.getMsg());
    }

    @Test
    void updateCasRecordsActualChangesAndRejectsStaleRevision() throws Exception {
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            var req=updateRequest(); service.updateRow(req,owner);
            assertThat(scalar("SELECT row_json->>'VALUE' FROM dh_asset_import_row WHERE id=11")).isEqualTo("0.12345678901234567890");
            assertThat(scalar("SELECT count(*)::text FROM dh_asset_import_row_log")).isEqualTo("1");
            assertThat(service.logs(1L,11L,1,20).getList().getFirst().getChanges().getFirst().getOldValue())
                    .isEqualTo("12345678901234567890.12345678901234567890");
            assertThatThrownBy(()->service.updateRow(req,owner)).hasMessageContaining(DATA_ASSET_CATALOG_REVISION_CONFLICT.getMsg());
            assertThat(scalar("SELECT count(*)::text FROM dh_asset_import_row_log")).isEqualTo("1");
        }
    }

    @Test
    void wrongOwnerRowAndPrimaryKeyChangesAreRejected() {
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            var req=updateRequest();req.setId(2L);
            assertThatThrownBy(()->service.updateRow(req,owner)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
            req.setId(1L);req.setRowId(999L);
            assertThatThrownBy(()->service.updateRow(req,owner)).hasMessageContaining(DATA_ASSET_IMPORT_ROW_NOT_EXISTS.getMsg());
            req.setRowId(11L);req.setValues(Map.of("ID","1"));
            assertThatThrownBy(()->service.updateRow(req,owner)).hasMessageContaining(DATA_ASSET_IMPORT_INPUT_INVALID.getMsg());
        }
    }

    @Test
    void failedAuditRollsBackRowAndHistoryTogether() throws Exception {
        doThrow(new IllegalStateException("审计失败")).when(audit).record(any(),any(),any(),any(),any(),any(),any());
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            assertThatThrownBy(()->service.updateRow(updateRequest(),owner)).hasMessageContaining("审计失败");
            assertThat(scalar("SELECT revision::text FROM dh_asset_import_row WHERE id=11")).isEqualTo("0");
            assertThat(scalar("SELECT count(*)::text FROM dh_asset_import_row_log")).isEqualTo("0");
        }
    }

    @Test
    void columnRevisionIsSharedAndRejectsLostUpdates() {
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            var req=new DataAssetColumnUpdateReqVO();req.setAssetId(1L);req.setId(2L);req.setRevision(0);req.setBusinessName("水位");
            service.updateColumn(req,owner);
            assertThat(service.column(1L,2L).getBusinessName()).isEqualTo("水位");
            assertThat(service.column(1L,2L).getRevision()).isEqualTo(1);
            assertThatThrownBy(()->service.updateColumn(req,owner)).hasMessageContaining(DATA_ASSET_CATALOG_REVISION_CONFLICT.getMsg());
        }
    }

    @Test
    void logTimeAndNicknameFiltersUseLocalPersistedActors() throws Exception {
        sql("INSERT INTO dh_asset_import_row_log(asset_id,row_id,revision,before_json,after_json,creator,create_time) VALUES"
                + "(1,11,1,'{}','{\"NAME\":\"水位\"}','9','2026-01-02'),"
                + "(1,11,2,'{}','{\"NAME\":\"流量\"}','10','2026-01-03')");
        var user = new cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO();user.setId(9L);
        when(users.getUserListByNickname("维护者")).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(List.of(user)));
        var from = java.time.LocalDateTime.parse("2026-01-01T00:00:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        var until = java.time.LocalDateTime.parse("2026-01-02T23:59:59").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(service.logs(1L,null,1,20,List.of(from,until),"维护者").getTotal()).isEqualTo(1);
        assertThat(service.logs(1L,null,1,20,List.of(from,until),null).getTotal()).isEqualTo(1);
        when(users.getUserListByNickname("不存在")).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(List.of()));
        assertThat(service.logs(1L,null,1,20,null,"不存在").getTotal()).isZero();
    }

    private DataAssetPreviewReqVO previewRequest() {var req=new DataAssetPreviewReqVO();req.setId(1L);req.setPageSize(1);return req;}
    private cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO asset() {
        return cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO.builder().id(1L).build();
    }
    private AssetImportRowUpdateReqVO updateRequest() {var req=new AssetImportRowUpdateReqVO();req.setId(1L);req.setRowId(11L);req.setRevision(0);req.setValues(Map.of("VALUE","0.12345678901234567890"));return req;}
    private void sql(String sql) throws Exception {try(var s=connection.createStatement()){s.execute(sql);}}
    private String scalar(String sql) throws Exception {try(var s=connection.createStatement();var rows=s.executeQuery(sql)){rows.next();return rows.getString(1);}}
    @AfterAll
    void cleanup() throws Exception {
        if(connection!=null)connection.close();
        if(database!=null)try(var control=DriverManager.getConnection(controlUrl,"catalog_test","");var s=control.createStatement()){
            s.execute("DROP DATABASE "+database+" WITH (FORCE)");
        }
    }
}
