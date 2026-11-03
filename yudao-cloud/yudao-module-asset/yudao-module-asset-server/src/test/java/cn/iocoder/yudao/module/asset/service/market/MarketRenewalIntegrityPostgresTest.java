package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.AdditionalAnswers;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 续期写入完整性的真实PG事务与JSONB；身份Guard和普通审计为替身。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
@Timeout(30)
class MarketRenewalIntegrityPostgresTest extends SharingPostgresSupport {
    private static final LocalDateTime END = LocalDateTime.of(2041, 1, 1, 0, 0);
    private MarketResourceApplyMapper mapper;
    private MarketApplyService service;
    private DataSourceTransactionManager transactions;
    private SqlSessionTemplate session;
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0042_asset_grant-source-delivery.sql");
        // 真正验证从旧结构升级，而非因为公共测试夹具已有新列就误判迁移成功。
        sql("ALTER TABLE dh_market_resource_apply DROP COLUMN renewal_requested_end_time");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        sql("ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1");
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq')");
        sql("SELECT setval('dh_market_resource_apply_seq',100,false)");
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb");
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,apply_end_time,snapshot)
                  VALUES (7,5,9,2,1,0,3,'2040-01-01',
                  '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}');
                """);
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactions = new DataSourceTransactionManager(datasource);
        var configuration = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("renewal-test", new SpringManagedTransactionFactory(), datasource));
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(MarketCheckProcessMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(MarketResourceApplyMapper.class);
        service = service(mapper);
        TenantContextHolder.clear();
        var login = new LoginUser();
        login.setId(9L);
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private MarketApplyService service(MarketResourceApplyMapper applies) {
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applies);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(9L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        var display = mock(MarketDisplayHelper.class);
        when(display.nextBizNo(anyString(), anyLong())).thenAnswer(call -> "APP-" + call.getArgument(1));
        ReflectionTestUtils.setField(target, "displayHelper", display);
        var assets = mock(DataAssetMapper.class);
        var asset = new DataAssetDO();
        asset.setId(5L);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assets.selectById(5L)).thenReturn(asset);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var calendar = mock(MarketWorkingDayService.class);
        when(calendar.plusWorkingDays(any(LocalDateTime.class), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        ReflectionTestUtils.setField(target, "workingDayService", calendar);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (MarketApplyService) proxy.getProxy();
    }

    private static MarketRenewalReqVO request() {
        var request = new MarketRenewalReqVO();
        request.setId(7L);
        request.setOperationId("renew-1");
        request.setApplyEndTime(END);
        return request;
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }


    @ParameterizedTest
    @ValueSource(strings = {"dh_market_resource_apply", "dh_market_check_process"})
    void skippedInsertCannotReturnAnIncompleteRenewal(String table) throws Exception {
        String before = graph();
        sql("""
                CREATE FUNCTION skip_renewal_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_renewal_write BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION skip_renewal_write();
                """.formatted(table));
        assertConflict(() -> service.createRenewal(request()));
        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(audit);
        sql("DROP TRIGGER skip_renewal_write ON " + table);
        assertCreated(service.createRenewal(request()));
    }

    @Test
    void renewalCopiesPublishedJsonbSnapshotWithoutReinterpretingIt() throws Exception {
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb");
        String original = row("SELECT snapshot::text FROM dh_market_resource_apply WHERE id=7");
        Long id = service.createRenewal(request());
        assertCreated(id);
        assertThat(row("SELECT snapshot::text FROM dh_market_resource_apply WHERE id=" + id)).isEqualTo(original);
        assertThat(service.createRenewal(request())).isEqualTo(id);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void missingOrInvalidGeneratedKeyRollsBackTheActualInsertedRow(Long badId) throws Exception {
        String before = graph();
        var altered = mock(MarketResourceApplyMapper.class, AdditionalAnswers.delegatesTo(mapper));
        doAnswer(call -> {
            MarketResourceApplyDO renewal = call.getArgument(0);
            int rows = mapper.insertRenewal(renewal);
            renewal.setId(badId); // 仅模拟驱动/映射丢失主键，INSERT本身真实执行。
            return rows;
        }).when(altered).insertRenewal(any(MarketResourceApplyDO.class));
        assertConflict(() -> service(altered).createRenewal(request()));
        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(audit);
        assertCreated(service.createRenewal(request()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void abnormalInsertReportRollsBackTheActualInsertedRow(int report) throws Exception {
        String before = graph();
        var altered = mock(MarketResourceApplyMapper.class, AdditionalAnswers.delegatesTo(mapper));
        doAnswer(call -> {
            mapper.insertRenewal(call.getArgument(0));
            return report;
        }).when(altered).insertRenewal(any(MarketResourceApplyDO.class));
        assertConflict(() -> service(altered).createRenewal(request()));
        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(audit);
        assertCreated(service.createRenewal(request()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "PROCESS", "COMMIT"})
    void databaseFailureLeavesNoChildOrProcessAndSameOperationCanBeRetried(String stage) throws Exception {
        String before = graph();
        if (stage.equals("INSERT")) {
            sql("ALTER TABLE dh_market_resource_apply ADD CONSTRAINT reject_renewal CHECK(renewal_of_apply_id IS NULL)");
        } else if (stage.equals("PROCESS")) {
            sql("ALTER TABLE dh_market_check_process ADD CONSTRAINT reject_renewal CHECK(action<>'RENEW')");
        } else {
            sql("""
                    CREATE FUNCTION reject_renewal_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test renewal commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_renewal AFTER INSERT ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_renewal_commit();
                    """);
        }
        assertThatThrownBy(() -> service.createRenewal(request())).isInstanceOf(RuntimeException.class);
        assertThat(graph()).isEqualTo(before);
        if (!stage.equals("COMMIT")) verifyNoInteractions(audit);
        // 普通审计当前在提交前被调用；本例不把Mock调用当作强持久审计。
        if (stage.equals("COMMIT")) sql("DROP TRIGGER reject_renewal ON dh_market_resource_apply");
        else sql("ALTER TABLE " + (stage.equals("INSERT") ? "dh_market_resource_apply" : "dh_market_check_process")
                + " DROP CONSTRAINT reject_renewal");
        Long id = service.createRenewal(request());
        assertCreated(id);
        String committed = graph();
        assertThat(service.createRenewal(request())).isEqualTo(id);
        assertThat(graph()).isEqualTo(committed);
    }

    @Test
    void childPreservesAllInheritedFieldsButNotOldApprovalDeliveryOrAuditIdentity() throws Exception {
        sql("""
                UPDATE dh_market_resource_apply SET apply_no='original',title='原申请',asset_type=1,api_id=3,app_id=2,
                  demand_id=15,apply_dept_id=16,phone='test-only-phone',email='test@example.invalid',purpose='用途',
                  field_scope='Id',apply_note='原申请说明',apply_start_time='2020-01-01',apply_use_type=1,apply_use_times=99,
                  check_user_id=21,check_time='2020-02-01',check_note='原审核意见',catalog_version_id=12,
                  duty_matter='履职',legal_basis='依据',supplement_note='原补正说明',received_time='2020-01-02',
                  reply_deadline='2020-01-03',delivery_deadline='2020-01-04',delivery_status=2,
                  source_grant_id='original-source',source_revision=8,first_used_time='2020-03-01',
                  bpm_instance_id='original-process',remark='备注',creator='old-creator',updater='old-updater',
                  create_time='2020-01-01',update_time='2020-01-05' WHERE id=7
                """);
        String original = row("SELECT to_jsonb(t)::text FROM dh_market_resource_apply t WHERE id=7");
        String inherited = inheritedFields(7L);
        Long id = service.createRenewal(request());
        assertCreated(id);
        assertThat(row("SELECT to_jsonb(t)::text FROM dh_market_resource_apply t WHERE id=7")).isEqualTo(original);
        assertThat(inheritedFields(id)).isEqualTo(inherited);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND check_user_id IS NULL AND check_time IS NULL AND check_note IS NULL AND first_used_time IS NULL"
                + " AND bpm_instance_id IS NULL AND creator='9' AND updater='9' AND create_time>'2020-01-05'"
                + " AND supplement_note IS NULL AND received_time>'2020-01-05'"
                + " AND reply_deadline=received_time + interval '1 day'"
                + " AND delivery_deadline=received_time + interval '21 days'"
                + " AND update_time>'2020-01-05' AND deleted=0 AND apply_no<>'original'"
                + " AND apply_end_time='2041-01-01' AND renewal_requested_end_time='2041-01-01'"))
                .isEqualTo(1);
        verify(audit).record("RENEW", "APPLY", id, null, 1, "续期申请", "OK");
    }

    @Test
    void nullHistoricalSnapshotStaysNullAndDoesNotCreateNewGrantEvidence() throws Exception {
        sql("UPDATE dh_market_resource_apply SET snapshot=NULL WHERE id=7");
        Long id = service.createRenewal(request());
        assertCreated(id);
        assertThat(row("SELECT snapshot::text FROM dh_market_resource_apply WHERE id=" + id)).isNull();
    }

    @Test
    void independentRenewalCommitSurvivesCallerRollbackAndIsReplayable() throws Exception {
        var id = new java.util.concurrent.atomic.AtomicReference<Long>();
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            id.set(service.createRenewal(request()));
            assertThatCode(() -> assertCreated(id.get())).doesNotThrowAnyException();
            outer.setRollbackOnly();
        });
        assertCreated(id.get());
        assertThat(service.createRenewal(request())).isEqualTo(id.get());
    }

    @Test
    void unrelatedPrimaryKeyCollisionIsNotSwallowedOrRetried() throws Exception {
        sql("SELECT setval('dh_market_resource_apply_seq',7,false)");
        String before = graph();
        assertThatThrownBy(() -> service.createRenewal(request()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class).isNotInstanceOf(ServiceException.class);
        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  renew-normalized  ", "续期-'quote'", "renew-9007199254740993"})
    void operationIdIsBoundAsDataAndNormalizedForReplay(String operation) throws Exception {
        var req = request();
        req.setOperationId(operation);
        Long id = service.createRenewal(req);
        assertCreated(id);
        assertThat(row("SELECT renewal_operation_id FROM dh_market_resource_apply WHERE id=" + id))
                .isEqualTo(operation.strip());
        String committed = graph();
        assertThat(service.createRenewal(req)).isEqualTo(id);
        assertThat(graph()).isEqualTo(committed);
    }

    private String inheritedFields(long id) throws Exception {
        return row("SELECT (to_jsonb(t)-ARRAY['id','apply_no','apply_end_time','status','if_auth','paused','revision',"
                + "'source_revision','source_grant_id','delivery_status','check_user_id','check_time','check_note',"
                + "'first_used_time','bpm_instance_id','renewal_of_apply_id','renewal_operation_id',"
                + "'renewal_requested_end_time','supplement_note','received_time','reply_deadline','delivery_deadline',"
                + "'creator','updater','create_time','update_time','deleted'])::text"
                + " FROM dh_market_resource_apply t WHERE id=" + id);
    }

    private void assertCreated(Long id) throws Exception {
        assertThat(id).isNotNull().isPositive().isNotEqualTo(7L);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7"
                + " AND status=1 AND if_auth=0 AND paused=0 AND revision=1 AND delivery_status=0"
                + " AND source_revision IS NULL AND source_grant_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND action='RENEW' AND from_status IS NULL AND to_status=1 AND operator_user_id=9"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source")).isZero();
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_RENEWAL_CONFLICT.getCode()));
    }

    private String graph() throws Exception {
        return row("""
                SELECT jsonb_build_array(
                  (SELECT COALESCE(jsonb_agg(to_jsonb(a) ORDER BY id),'[]'::jsonb) FROM dh_market_resource_apply a),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY id),'[]'::jsonb) FROM dh_market_check_process p),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(s) ORDER BY id),'[]'::jsonb) FROM dh_asset_grant_source s),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY id),'[]'::jsonb) FROM dh_market_delivery_operation o)
                )::text
                """);
    }

    private String row(String query) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
