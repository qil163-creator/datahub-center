package cn.iocoder.yudao.module.asset.service.sensitive;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.sensitive.AssetSensitiveListMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.Modifier;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 真实数据库验证维护范围、可重复记录、启停 CAS、原子批量删除及同事务审计。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class AssetSensitiveListPostgreSqlTest {
    private Connection connection;
    private String controlUrl;
    private String database;
    private AssetSensitiveListService service;
    private AssetSensitiveListMapper mapper;
    private SensitiveClassificationReferenceApi safety;
    private AssetCatalogService catalog;
    private final ThreadLocal<Long> currentUser = ThreadLocal.withInitial(() -> 9L);
    private AssetMaintenanceAuthorization owner;

    @BeforeAll
    void setup() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        database = "sensitive_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(controlUrl, "catalog_test", ""); var s = c.createStatement()) { s.execute("CREATE DATABASE " + database); }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, "catalog_test", "");
        createEntity("dh_asset_data_asset", DataAssetDO.class);
        createEntity("dh_asset_sensitive_list", AssetSensitiveListDO.class);
        sql("CREATE TABLE dh_asset_import_dataset(asset_id bigint primary key,revision int,source_asset_id varchar,source_name varchar,source_type varchar,source_data_count bigint,imported_row_count bigint,complete boolean,columns_json jsonb,file_info_json jsonb,quality_json jsonb,lineage_json jsonb,creator varchar,updater varchar,create_time timestamp,update_time timestamp,deleted int default 0)");
        sql("CREATE TABLE dh_market_audit_event(id bigserial primary key,event_id varchar,event_type varchar,actor_user_id bigint,dept_id bigint,object_type varchar,object_id bigint,from_status varchar,to_status varchar,reason varchar,result varchar,occurred_at timestamp,creator varchar,updater varchar)");
        var source = new UnpooledDataSource("org.postgresql.Driver", url, "catalog_test", "");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("sensitive", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(pagination);
        for (var type : List.of(AssetSensitiveListMapper.class, DataAssetMapper.class, AssetImportDatasetMapper.class, MarketAuditEventMapper.class)) configuration.addMapper(type);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(AssetSensitiveListMapper.class);
        var assets = session.getMapper(DataAssetMapper.class); var datasets = session.getMapper(AssetImportDatasetMapper.class);
        var constructor = AssetMaintenanceAuthorization.class.getDeclaredConstructor(Long.class, Long.class, boolean.class, boolean.class);
        constructor.setAccessible(true); owner = constructor.newInstance(9L, 4L, false, false);
        catalog = mock(AssetCatalogService.class); safety = mock(SensitiveClassificationReferenceApi.class);
        var audit = new MarketAuditRecorder(); ReflectionTestUtils.setField(audit, "auditEventMapper", session.getMapper(MarketAuditEventMapper.class));
        var target = new AssetSensitiveListWriter();
        ReflectionTestUtils.setField(target, "mapper", mapper); ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "datasetMapper", datasets); ReflectionTestUtils.setField(target, "catalog", catalog);
        ReflectionTestUtils.setField(target, "audit", audit);
        var proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = new AssetSensitiveListService();
        ReflectionTestUtils.setField(service, "writer", proxy.getProxy()); ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "assetMapper", assets); ReflectionTestUtils.setField(service, "datasetMapper", datasets);
        ReflectionTestUtils.setField(service, "catalog", catalog); ReflectionTestUtils.setField(service, "safety", safety);
        var servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer sensitive-test-token");
        ReflectionTestUtils.setField(service, "servletRequest", servletRequest);
        ReflectionTestUtils.setField(service, "securityProperties", new SecurityProperties());
        var names = mock(AssetUserNameResolver.class); when(names.resolveByTexts(any())).thenReturn(Map.of());
        ReflectionTestUtils.setField(service, "names", names);

    }

    @BeforeEach
    void fixture() throws Exception {
        currentUser.set(9L); reset(catalog, safety);
        sql("TRUNCATE dh_asset_sensitive_list,dh_asset_data_asset,dh_asset_import_dataset,dh_market_audit_event RESTART IDENTITY");
        sql("INSERT INTO dh_asset_data_asset(id,asset_name,table_name,status,asset_type,owner_dept_id,creator,catalog_revision,deleted) VALUES(1,'河流','RIVER',0,1,4,'9',0,0),(2,'其他部门','SECRET',0,1,8,'10',0,0)");
        sql("INSERT INTO dh_asset_import_dataset(asset_id,revision,deleted) VALUES(1,0,0),(2,0,0)");
        when(catalog.prepareMaintenanceAuthorization()).thenAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return owner; });
        doAnswer(inv -> {
            var asset = (DataAssetDO) inv.getArgument(0);
            if (!Objects.equals(currentUser.get(), owner.getUserId()) || !owner.canMaintain(asset))
                throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(DATA_ASSET_FORBIDDEN);
            return null;
        }).when(catalog).assertCanMaintain(any(), any());
        var column = new MetadataColumnRespDTO(); column.setId(101L); column.setColumnName("ID");
        when(catalog.listColumns(anyLong(), isNull())).thenAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return List.of(column); });
        var category = category(24L, "dir:1");
        when(safety.getClassification(24L, "Bearer sensitive-test-token")).thenAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return CommonResult.success(category); });
        var directory = new SafetyClassificationRespDTO(); directory.setId("dir:1"); directory.setName("水利"); directory.setSelectable(false); directory.setStatus((short) 0);
        when(safety.getClassifications("Bearer sensitive-test-token")).thenReturn(CommonResult.success(List.of(directory, category)));
    }

    @Test void duplicateDefinitionsAreIndependentAndSourceOrClientNamesCannotReplaceCurrentColumn() throws Exception {
        Long first = service.create(request()); Long second = service.create(request());
        assertThat(first).isNotEqualTo(second);
        assertThat(scalar("SELECT count(*) FROM dh_asset_sensitive_list")).isEqualTo("2");
        assertThat(scalar("SELECT min(column_name) FROM dh_asset_sensitive_list")).isEqualTo("ID");
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='SENSITIVE_CREATE' AND actor_user_id=9")).isEqualTo("2");
    }

    @Test void statusCasRejectsStaleClientAndEnabledRowsRejectEditAndDelete() throws Exception {
        Long id = service.create(request()); service.changeStatus(status(id, 0, 0));
        assertThatThrownBy(() -> service.changeStatus(status(id, 0, 1))).hasMessageContaining(ASSET_SENSITIVE_REVISION_CONFLICT.getMsg());
        var update = request(); update.setId(id); update.setRevision(1);
        assertThatThrownBy(() -> service.update(update)).hasMessageContaining(ASSET_SENSITIVE_ENABLED.getMsg());
        assertThatThrownBy(() -> service.delete(List.of(item(id, 1)), true)).hasMessageContaining(ASSET_SENSITIVE_ENABLED.getMsg());
        assertThat(scalar("SELECT status||':'||revision FROM dh_asset_sensitive_list WHERE id=" + id)).isEqualTo("0:1");
    }

    @Test void updateMayEnableAtomicallyAndRecordsActualLocalActor() throws Exception {
        Long id = service.create(request()); var update = request(); update.setId(id); update.setRevision(0); update.setStatus(0); update.setDescription("监测标识");
        service.update(update);
        assertThat(scalar("SELECT status||':'||revision||':'||description FROM dh_asset_sensitive_list WHERE id=" + id)).isEqualTo("0:1:监测标识");
        assertThat(scalar("SELECT to_status FROM dh_market_audit_event WHERE event_type='SENSITIVE_UPDATE'")).isEqualTo("0");
    }

    @Test void concurrentStatusRequestsHaveExactlyOneRevisionWinner() throws Exception {
        Long id = service.create(request());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) calls.add(workers.submit(() -> {
                start.await();
                try { service.changeStatus(status(id, 0, 0)); return true; }
                catch (cn.iocoder.yudao.framework.common.exception.ServiceException e) {
                    assertThat(e.getCode()).isEqualTo(ASSET_SENSITIVE_REVISION_CONFLICT.getCode()); return false;
                }
            }));
            start.countDown(); int winners = 0;
            for (var call : calls) if (call.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='SENSITIVE_STATUS'")).isEqualTo("1");
    }

    @Test void anyStaleBatchRevisionLeavesAllSelectedRowsAndAuditUnchanged() throws Exception {
        Long first = service.create(request()); Long second = service.create(request());
        assertThatThrownBy(() -> service.delete(List.of(item(first, 0), item(second, 7)), false))
                .hasMessageContaining(ASSET_SENSITIVE_REVISION_CONFLICT.getMsg());
        assertThat(mapper.selectById(first)).isNotNull(); assertThat(mapper.selectById(second)).isNotNull();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='SENSITIVE_DELETE'")).isEqualTo("0");
    }

    @Test void directoryFilterAndPageScopeExcludeForeignAssetBeforePagination() throws Exception {
        sql("UPDATE dh_asset_data_asset SET description='资产自身说明' WHERE id=1");
        service.create(request()); sql("INSERT INTO dh_asset_sensitive_list(id,asset_id,column_id,column_name,category_id,classification_name,status,revision,deleted) VALUES(2,2,101,'SECRET',24,'站码',1,0,0)");
        var req = new AssetSensitivePageReqVO(); req.setCategoryKey("dir:1"); req.setPageSize(1);
        var result = service.page(req); assertThat(result.getTotal()).isEqualTo(1); assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().getFirst().getAssetId()).isEqualTo(1L);
        assertThat(result.getList().getFirst().getAssetDescription()).isEqualTo("资产自身说明");
        req.setKeyword("STCD");
        assertThat(service.page(req).getTotal()).isZero();
        req.setKeyword("河流");
        assertThat(service.page(req).getTotal()).isEqualTo(1);
        req.setKeyword(null);
        req.setCategoryKey("dir:999"); assertThat(service.page(req).getTotal()).isZero();
        assertThatThrownBy(() -> service.get(2L)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        assertThatThrownBy(() -> service.columnOptions(2L)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        assertThat(service.assetOptions(new AssetSensitivePageReqVO()).getTotal()).isEqualTo(1);
    }

    @Test void mixedBatchSkipsEnabledButAnyForeignSelectionRollsBackWholeBatch() throws Exception {
        Long disabled = service.create(request()); Long enabled = service.create(request()); service.changeStatus(status(enabled, 0, 0));
        sql("INSERT INTO dh_asset_sensitive_list(id,asset_id,column_id,column_name,category_id,status,revision,deleted) VALUES(2,2,101,'SECRET',24,1,0,0)");
        assertThatThrownBy(() -> service.delete(List.of(item(disabled, 0), item(2L, 0)), false)).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        assertThat(mapper.selectById(disabled)).isNotNull();
        var result = service.delete(List.of(item(disabled, 0), item(enabled, 1)), false);
        assertThat(result.deletedIds()).containsExactly(disabled); assertThat(result.skippedIds()).containsExactly(enabled);
        assertThat(mapper.selectById(disabled)).isNull(); assertThat(mapper.selectById(enabled)).isNotNull();
    }

    @Test void unknownColumnDisabledCategoryAndChangedImportRevisionAreRejected() throws Exception {
        var req = request(); req.setColumnId(999L);
        assertThatThrownBy(() -> service.create(req)).hasMessageContaining(ASSET_SENSITIVE_COLUMN_INVALID.getMsg());
        req.setColumnId(101L); var disabled = category(24L, "dir:1"); disabled.setStatus((short) 1);
        when(safety.getClassification(24L, "Bearer sensitive-test-token")).thenReturn(CommonResult.success(disabled));
        assertThatThrownBy(() -> service.create(req)).hasMessageContaining(ASSET_SENSITIVE_CATEGORY_INVALID.getMsg());
        when(safety.getClassification(24L, "Bearer sensitive-test-token")).thenAnswer(inv -> {
            sql("UPDATE dh_asset_import_dataset SET revision=revision+1 WHERE asset_id=1"); return CommonResult.success(category(24L, "dir:1"));
        });
        doAnswer(inv -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) sql("UPDATE dh_asset_import_dataset SET revision=revision+1 WHERE asset_id=1");
            return null;
        }).when(catalog).assertCanMaintain(any(), any());
        assertThatThrownBy(() -> service.create(req)).hasMessageContaining(ASSET_SENSITIVE_REVISION_CONFLICT.getMsg());
    }

    @Test void ownershipIsRecheckedAfterLockAndAuditFailureRollsBackBusinessWrite() throws Exception {
        doAnswer(inv -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(DATA_ASSET_FORBIDDEN);
            return null;
        }).when(catalog).assertCanMaintain(any(), any());
        assertThatThrownBy(() -> service.create(request())).hasMessageContaining(DATA_ASSET_FORBIDDEN.getMsg());
        assertThat(scalar("SELECT count(*) FROM dh_asset_sensitive_list")).isEqualTo("0");
        doNothing().when(catalog).assertCanMaintain(any(), any());
        sql("ALTER TABLE dh_market_audit_event ADD CONSTRAINT reject_audit CHECK(event_type <> 'SENSITIVE_CREATE')");
        try {
            assertThatThrownBy(() -> service.create(request())).isInstanceOf(RuntimeException.class);
            assertThat(scalar("SELECT count(*) FROM dh_asset_sensitive_list")).isEqualTo("0");
        } finally { sql("ALTER TABLE dh_market_audit_event DROP CONSTRAINT reject_audit"); }
    }

    @AfterAll void cleanup() throws Exception {
        if (connection != null) connection.close();
        if (database != null) try (var c = DriverManager.getConnection(controlUrl, "catalog_test", ""); var s = c.createStatement()) { s.execute("DROP DATABASE " + database + " WITH(FORCE)"); }
    }

    @Test void explicitRuleBindingIsPersistedAndNeverGuessedFromDisplayName() throws Exception {
        var definition = category(24L, "dir:1"); definition.setRuleId(9007199254740993L);
        when(safety.getClassification(24L, "Bearer sensitive-test-token")).thenReturn(CommonResult.success(definition));
        Long id = service.create(request());
        assertThat(scalar("SELECT rule_id FROM dh_asset_sensitive_list WHERE id=" + id)).isEqualTo("9007199254740993");
        var query = new AssetSensitivePageReqVO(); query.setRuleId(9007199254740993L);
        assertThat(service.page(query).getList()).extracting(AssetSensitiveRespVO::getId).containsExactly(id);
        query.setRuleId(20L); assertThat(service.page(query).getTotal()).isZero();
        sql("UPDATE dh_asset_sensitive_list SET rule_id=NULL,rule_name='与某规则同名' WHERE id=" + id);
        assertThat(service.page(query).getTotal()).isZero();
    }

    private AssetSensitiveSaveReqVO request() { var req = new AssetSensitiveSaveReqVO(); req.setAssetId(1L); req.setColumnId(101L); req.setCategoryId(24L); return req; }
    private AssetSensitiveStatusReqVO status(Long id, int revision, int state) { var req = new AssetSensitiveStatusReqVO(); req.setId(id); req.setRevision(revision); req.setStatus(state); return req; }
    private AssetSensitiveDeleteReqVO.Item item(Long id, int revision) { var req = new AssetSensitiveDeleteReqVO.Item(); req.setId(id); req.setRevision(revision); return req; }
    private SafetyClassificationRespDTO category(Long id, String parent) { var c = new SafetyClassificationRespDTO(); c.setId("category:" + id); c.setParentId(parent); c.setCategoryId(id); c.setName("站码"); c.setSelectable(true); c.setStatus((short) 0); c.setLevelName("一般"); return c; }
    private void sql(String value) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(value); } }
    private String scalar(String value) throws SQLException { try (var statement = connection.createStatement(); var result = statement.executeQuery(value)) { result.next(); return result.getString(1); } }
    private void createEntity(String table, Class<?> type) throws SQLException {
        var columns = new LinkedHashMap<String, String>();
        for (var current = type; current != Object.class; current = current.getSuperclass()) for (var field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("transMap")) continue;
            Class<?> t = field.getType();
            String dbType = t == Long.class ? "bigint" : t == Integer.class || t == Boolean.class ? "integer" : t == java.time.LocalDateTime.class ? "timestamp" : t == java.math.BigDecimal.class ? "numeric" : "varchar";
            String name = StrUtil.toUnderlineCase(field.getName());
            columns.put(name, name + " " + dbType + (name.equals("id") ? " PRIMARY KEY" : name.equals("deleted") ? " DEFAULT 0" : ""));
        }
        sql("CREATE TABLE " + table + "(" + String.join(",", columns.values()) + ")");
    }
}
