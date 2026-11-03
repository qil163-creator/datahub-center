package cn.iocoder.yudao.module.governance.service.whitelist;
import cn.iocoder.yudao.module.governance.service.category.*;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.*;
import cn.iocoder.yudao.module.governance.api.security.dto.*;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.*;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.*;
import cn.iocoder.yudao.module.governance.dal.mysql.subject.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.*;
import cn.iocoder.yudao.module.system.api.user.*;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import cn.iocoder.yudao.framework.common.pojo.*;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;


import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.*;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyFieldPermissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.replaceitem.SafetyReplaceItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rulever.SafetyRuleVerMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.service.categorydata.*;
import cn.iocoder.yudao.module.governance.service.rule.*;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;

/** 仅使用自建临时 PostgreSQL，覆盖版本发布、分类 CAS、删除引用及真实导入事实。 */
@EnabledIfEnvironmentVariable(named = "CLASSIFICATION_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class SafetyMaskWorkbenchPostgreSqlTest {
    private String controlUrl, database, user;
    private Connection connection;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private SafetyCategoryService categories;
    private CategoryDataService directories;
    private SafetyRuleService rules;
    private SafetyCategoryMaskService masks;
    private SafetyCategoryMapper categoryMapper;
    private SafetyRuleWorkbenchService workbench;
    private SafetyWhitelistWorkbenchService white;
    private SafetyWhitelistWorkbenchWriter writer;
    private SafetyWhitelistService nativeWhite;
    private SafetyFieldMaskService fields;
    private AdminUserApi people;


    @BeforeAll void createOnlyOwnedDatabaseAndLoadBaseline() throws Exception {
        controlUrl = System.getenv("CLASSIFICATION_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres").doesNotContain(":5433/");
        user = System.getenv().getOrDefault("CLASSIFICATION_TEST_JDBC_USER", "catalog_test");
        database = "mask_workbench_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, user, ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, user, "");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
        sql(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
        sql("SET search_path TO \"datahub-center\",public");
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=datahub-center&socketTimeout=15", user, "");
        transactions = new DataSourceTransactionManager(dataSource);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setEnvironment(new Environment("classification-isolated", new SpringManagedTransactionFactory(), dataSource));
        var plugin = new MybatisPlusInterceptor(); plugin.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(plugin);
        for (Class<?> type : List.of(SafetyCategoryMapper.class, CategoryDataMapper.class, SafetyLevelMapper.class,
                SafetyIdentifyRuleMapper.class, SafetyFieldTagMapper.class, SafetyRuleMapper.class, SafetyRuleVerMapper.class,
                SafetyReplaceItemMapper.class, SafetyFieldPermissionMapper.class, SafetyStaticTaskMapper.class,
                SafetyRuleWorkbenchMapper.class,SafetyWhitelistMapper.class,SafetyWhitelistWorkbenchMapper.class,
                SafetyWhitelistSubjectMapper.class,SafetyWhitelistApprovalMapper.class)) config.addMapper(type);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        categoryMapper = session.getMapper(SafetyCategoryMapper.class);
        var ruleTarget = new SafetyRuleServiceImpl();
        inject(ruleTarget, Map.of("ruleMapper", SafetyRuleMapper.class, "ruleVerMapper", SafetyRuleVerMapper.class,
                "replaceItemMapper", SafetyReplaceItemMapper.class, "fieldPermissionMapper", SafetyFieldPermissionMapper.class,
                "staticTaskMapper", SafetyStaticTaskMapper.class, "categoryMapper", SafetyCategoryMapper.class));
        rules = proxy(ruleTarget);
        var categoryTarget = new SafetyCategoryServiceImpl();
        inject(categoryTarget, Map.of("categoryMapper", SafetyCategoryMapper.class, "directoryMapper", CategoryDataMapper.class,
                "levelMapper", SafetyLevelMapper.class, "identifyRuleMapper", SafetyIdentifyRuleMapper.class, "fieldTagMapper", SafetyFieldTagMapper.class));
        categories = proxy(categoryTarget);
        var directoryTarget = new CategoryDataServiceImpl();
        inject(directoryTarget, Map.of("categoryDataMapper", CategoryDataMapper.class, "safetyCategoryMapper", SafetyCategoryMapper.class));
        directories = proxy(directoryTarget);
        var maskTarget = new SafetyCategoryMaskService();
        ReflectionTestUtils.setField(maskTarget, "categories", categoryMapper); ReflectionTestUtils.setField(maskTarget, "rules", rules);
        masks = proxy(maskTarget);
        people=mock(AdminUserApi.class);
        when(people.validateUserList(any())).thenReturn(CommonResult.success(true));
        when(people.getUserMap(any())).thenAnswer(call->{
            Collection<Long> ids=call.getArgument(0); var result=new HashMap<Long,AdminUserRespDTO>();
            for(Long id:ids){var user=new AdminUserRespDTO();user.setId(id);user.setNickname("本地用户"+id);user.setStatus(0);result.put(id,user);} return result;
        });
        var references=new SafetyClassificationReferenceService();
        inject(references,Map.of("directoryMapper",CategoryDataMapper.class,"categoryMapper",SafetyCategoryMapper.class,
                "levelMapper",SafetyLevelMapper.class,"ruleMapper",SafetyRuleMapper.class));
        var ruleBench=new SafetyRuleWorkbenchService();
        inject(ruleBench,Map.of("workbenchMapper",SafetyRuleWorkbenchMapper.class,"categoryMapper",SafetyCategoryMapper.class,
                "directoryMapper",CategoryDataMapper.class,"ruleMapper",SafetyRuleMapper.class));
        ReflectionTestUtils.setField(ruleBench,"ruleService",rules); ReflectionTestUtils.setField(ruleBench,"categoryMasks",masks);
        ReflectionTestUtils.setField(ruleBench,"users",people); workbench=proxy(ruleBench);
        var whiteWriter=new SafetyWhitelistWorkbenchWriter();
        inject(whiteWriter,Map.of("mapper",SafetyWhitelistMapper.class,"subjects",SafetyWhitelistSubjectMapper.class,
                "approvals",SafetyWhitelistApprovalMapper.class,"categories",SafetyCategoryMapper.class));
        ReflectionTestUtils.setField(whiteWriter,"definitions",references); writer=proxy(whiteWriter);
        white=new SafetyWhitelistWorkbenchService();
        inject(white,Map.of("mapper",SafetyWhitelistWorkbenchMapper.class,"nativeMapper",SafetyWhitelistMapper.class,
                "subjects",SafetyWhitelistSubjectMapper.class,"categories",SafetyCategoryMapper.class,"directories",CategoryDataMapper.class));
        ReflectionTestUtils.setField(white,"writer",writer);ReflectionTestUtils.setField(white,"users",people);
        var nativeTarget=new SafetyWhitelistServiceImpl();
        inject(nativeTarget,Map.of("whitelistMapper",SafetyWhitelistMapper.class,"whitelistSubjectMapper",SafetyWhitelistSubjectMapper.class,
                "approvalMapper",SafetyWhitelistApprovalMapper.class));
        ReflectionTestUtils.setField(nativeTarget,"adminUserApi",people);nativeWhite=proxy(nativeTarget);
        fields=new SafetyFieldMaskService();
        ReflectionTestUtils.setField(fields,"whitelists",nativeWhite);ReflectionTestUtils.setField(fields,"categories",categoryMapper);
        ReflectionTestUtils.setField(fields,"rules",rules);
    }

    @BeforeEach void login(){loginActor();}
    @AfterEach void clearLogin(){SecurityContextHolder.clearContext();}

    @Test void importedRulesAndUserConfirmedCategoryWhitelistAreRealLocalScopedPolicies() throws Exception {
        var req=new SafetyRuleWorkbenchPageReqVO(); req.setPageSize(100);
        assertThat(workbench.page(req).getList().stream().filter(v->v.getSourceRecordId()!=null).toList()).hasSize(52);
        req.setApplicationScene("2");assertThat(workbench.page(req).getTotal()).isGreaterThanOrEqualTo(52);
        var list=new SafetyWhitelistWorkbenchPageReqVO();list.setPageSize(100);
        var imported=white.page(list).getList().stream().filter(v->v.getSourceRecordId()!=null).toList();
        assertThat(imported).hasSize(3).allSatisfy(v->{
            assertThat(v.getUserIds()).containsExactly(1L);assertThat(v.getStatus()).isZero();
            assertThat(v.getAuthorizationBasis()).isEqualTo("USER_CONFIRMED_IMPORT");
            assertThat(v.getEndTime()).isEqualTo(LocalDate.of(2099,12,31));
        });
        assertThat(nativeWhite.getMaskExemptStrict(1L,"QUERY").getExempt()).isFalse();
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"QUERY",Set.of(25L,27L,44L,6L))).containsExactlyInAnyOrder(25L,27L,44L);
        assertThat(nativeWhite.getCategoryExemptionsStrict(2L,"QUERY",Set.of(25L,27L,44L))).isEmpty();
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"API",Set.of(25L))).isEmpty();
        assertThat(count("SELECT count(*) FROM dh_governance_safety_whitelist_approval WHERE action='IMPORT_AUTHORIZATION' AND creator='migration:0235'")).isEqualTo(3);
    }

    @Test void actualFieldCategoryMaskingIsLocalUserSpecificAndCannotExemptAnotherCategory() {
        var request=fieldRequest(1L,Map.of("STCD",List.of(25L),"OTHER",List.of(6L)),Map.of("STCD","ABC123","OTHER","ABCDEFG","plain","保持"));
        var result=fields.mask(request);
        assertThat(result.getRows().getFirst().get("STCD")).isEqualTo("ABC123");
        assertThat(result.getRows().getFirst().get("OTHER")).isNotEqualTo("ABCDEFG");
        assertThat(result.getRows().getFirst().get("plain")).isEqualTo("保持");
        request.setUserId(2L);var denied=fields.mask(request);
        assertThat(denied.getRows().getFirst().get("STCD")).isNotEqualTo("ABC123");
        assertThat(denied.getPolicyFingerprint()).isNotEqualTo(result.getPolicyFingerprint());
        request.setFields(List.of(request.getFields().getFirst(),request.getFields().getFirst()));
        assertThatThrownBy(()->fields.mask(request)).isInstanceOf(ServiceException.class);
    }

    @Test void newWhitelistUsesAuditedLocalAuthorizationAndDateInclusiveEnd() throws Exception {
        var req=whiteSave(createCategory("白名单创建"));Long id=white.create(req);
        var row=white.get(id);assertThat(row.getRevision()).isEqualTo(1L);assertThat(row.getStatus()).isEqualTo((short)1);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_whitelist_approval WHERE whitelist_id="+id+" AND action='LOCAL_POLICY_AUTHORIZATION'")).isEqualTo(1);
        var nativeRow=session.getMapper(SafetyWhitelistMapper.class).selectById(id);
        assertThat(nativeRow.getApprovalStatus()).isEqualTo("AUTHORIZED");assertThat(nativeRow.getApprover()).isNull();
        assertThat(nativeRow.getExpireTime()).isEqualTo(req.getEndTime().plusDays(1).atStartOfDay().minusNanos(1000));
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"QUERY",Set.of(req.getCategoryId()))).isEmpty();
        var state=new SafetyWhitelistWorkbenchStatusReqVO();state.setId(id);state.setRevision(1L);state.setStatus((short)0);white.changeStatus(state);
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"QUERY",Set.of(req.getCategoryId()))).containsExactly(req.getCategoryId());
        assertThat(white.get(id).getRevision()).isEqualTo(2L);
    }

    @Test void staleWhitelistWriteReadOnlyCategoryAndEnabledDeleteAreRejectedAtomically() throws Exception {
        var req=whiteSave(createCategory("白名单并发"));Long id=white.create(req);req.setId(id);req.setRevision(1L);req.setDescription("新说明");white.update(req);
        assertThatThrownBy(()->white.update(req)).isInstanceOf(ServiceException.class).extracting("code").isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode());
        req.setRevision(2L);req.setCategoryId(createCategory("不能移动"));assertThatThrownBy(()->white.update(req)).isInstanceOf(ServiceException.class);
        var status=new SafetyWhitelistWorkbenchStatusReqVO();status.setId(id);status.setRevision(2L);status.setStatus((short)0);white.changeStatus(status);
        var version=new SafetyWhitelistWorkbenchVersionReqVO();version.setId(id);version.setRevision(3L);
        assertThatThrownBy(()->white.delete(version)).isInstanceOf(ServiceException.class);
        assertThat(white.get(id).getRevision()).isEqualTo(3L);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_whitelist_subject WHERE whitelist_id="+id+" AND deleted=0")).isEqualTo(1);
    }

    @Test void genericEntryMutationAdvancesWorkbenchRevisionWithoutInventingSourceUpdater() throws Exception {
        var req=whiteSave(createCategory("旧入口版本"));Long id=white.create(req);
        sql("UPDATE dh_governance_safety_whitelist SET description='其他正式入口',updater='8' WHERE id="+id);
        var row=white.get(id);assertThat(row.getRevision()).isEqualTo(2L);
        req.setId(id);req.setRevision(1L);assertThatThrownBy(()->white.update(req)).isInstanceOf(ServiceException.class);
        assertThat(row.getDescription()).isEqualTo("其他正式入口");
    }

    @Test void disabledRuleDeletionReleasesOnlyOwnCategoryAndEnabledBulkRollsBack() throws Exception {
        var disabled=workbench.get(ruleId("52"));var enabled=workbench.get(ruleId("1"));
        var items=new SafetyRuleWorkbenchBatchReqVO();items.setItems(List.of(ruleVersion(disabled),ruleVersion(enabled)));
        assertThatThrownBy(()->workbench.deleteList(items)).isInstanceOf(ServiceException.class);
        assertThat(workbench.get(disabled.getId()).getId()).isEqualTo(disabled.getId());
        var created=workbench.create(ruleSave(createCategory("可删除规则")));
        var before=workbench.get(created);workbench.delete(ruleVersion(before));
        assertThat(session.getMapper(SafetyRuleMapper.class).selectById(created)).isNull();
        assertThat(categoryMapper.selectById(before.getCategoryId()).getRuleId()).isNull();
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id="+created)).isPositive();
    }

    @Test void ruleWorkbenchPreservesHiddenSortAndRejectsIndependentMetadataOnlyUpdate() throws Exception {
        var create=ruleSave(createCategory("配置并发"));Long id=workbench.create(create);
        sql("UPDATE dh_governance_safety_rule SET sort=77 WHERE id="+id);
        var before=workbench.get(id);var update=ruleSave(before.getCategoryId());update.setId(id);update.setCategoryRevision(before.getCategoryRevision());
        update.setRuleVersionId(before.getRuleVersionId());update.setRuleRevisionToken(before.getRuleRevisionToken());update.setDescription("工作台说明");
        workbench.update(update);assertThat(rules.getRule(id).getSort()).isEqualTo(77);
        var latest=workbench.get(id);update.setCategoryRevision(latest.getCategoryRevision());update.setRuleVersionId(latest.getRuleVersionId());update.setRuleRevisionToken(latest.getRuleRevisionToken());
        var nativeReq=new SafetyRuleSaveReqVO();nativeReq.setId(id);nativeReq.setRuleName("独立入口的新名称");nativeReq.setMethodCode("MASK");nativeReq.setStatus((short)1);nativeReq.setSort(77);
        nativeReq.setConfig(Map.of("intervals",List.of(Map.of("start",1,"end",1)),"maskChar","*","applicationScene",List.of("1","2"),"maskType","2","replaceRule","1"));
        rules.updateRule(nativeReq);
        assertThatThrownBy(()->workbench.update(update)).isInstanceOf(ServiceException.class);
        assertThat(rules.getRule(id).getRuleName()).isEqualTo("独立入口的新名称");
    }

    @Test void realConcurrentWhitelistWritesHaveOneWinnerAndCannotLoseCommittedState() throws Exception {
        Long category=createCategory("并发授权");var first=whiteSave(category);Long id=white.create(first);
        var second=whiteSave(category);first.setId(id);first.setRevision(1L);first.setDescription("A");second.setId(id);second.setRevision(1L);second.setDescription("B");
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            var jobs=List.of(first,second).stream().map(req->pool.submit(()->{
                loginActor();ready.countDown();start.await();
                try {white.update(req);return true;} catch(ServiceException failure){assertThat(failure.getCode()).isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode());return false;}
                finally {SecurityContextHolder.clearContext();}
            })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            int success=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);assertThat(white.get(id).getRevision()).isEqualTo(2L);
            assertThat(white.get(id).getDescription()).isIn("A","B");
        } finally {start.countDown();pool.shutdownNow();}
    }

    @Test void whitelistBatchDeletionRollsBackAllIfAnyItemIsEnabledAndPreservesTombstone() throws Exception {
        Long a=white.create(whiteSave(createCategory("批删一"))),b=white.create(whiteSave(createCategory("批删二")));
        var status=new SafetyWhitelistWorkbenchStatusReqVO();status.setId(b);status.setRevision(1L);status.setStatus((short)0);white.changeStatus(status);
        var va=new SafetyWhitelistWorkbenchVersionReqVO();va.setId(a);va.setRevision(1L);
        var vb=new SafetyWhitelistWorkbenchVersionReqVO();vb.setId(b);vb.setRevision(2L);
        var batch=new SafetyWhitelistWorkbenchBatchReqVO();batch.setItems(List.of(va,vb));
        assertThatThrownBy(()->white.deleteList(batch)).isInstanceOf(ServiceException.class);
        assertThat(white.get(a).getRevision()).isEqualTo(1L);assertThat(white.get(b).getRevision()).isEqualTo(2L);
        white.delete(va);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_whitelist WHERE id="+a+" AND deleted=1 AND revision=2")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_whitelist_subject WHERE whitelist_id="+a+" AND deleted=0")).isZero();
    }

    @Test void directPolicyNeedsLocalDecisionProofAndCannotBeDowngradedThroughOldApprovalEntry() throws Exception {
        Long category=createCategory("授权证明");var req=whiteSave(category);Long id=white.create(req);
        assertThatThrownBy(()->nativeWhite.submitWhitelist(id)).isInstanceOf(ServiceException.class);
        sql("UPDATE dh_governance_safety_whitelist SET status=0 WHERE id="+id);
        sql("UPDATE dh_governance_safety_whitelist_approval SET deleted=1 WHERE whitelist_id="+id);
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"QUERY",Set.of(category))).isEmpty();
        var status=new SafetyWhitelistWorkbenchStatusReqVO();status.setId(id);status.setRevision(2L);status.setStatus((short)0);white.changeStatus(status);
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"QUERY",Set.of(category))).containsExactly(category);
        var mapper=session.getMapper(SafetyWhitelistMapper.class);
        assertThat(mapper.selectActiveList("QUERY",req.getEndTime().plusDays(1).atStartOfDay().minusNanos(1000)).stream().map(v->v.getId())).contains(id);
        assertThat(mapper.selectActiveList("QUERY",req.getEndTime().plusDays(1).atStartOfDay()).stream().map(v->v.getId())).doesNotContain(id);
    }

    @Test void categoryRuleApplicationSceneIsRespectedWithoutAddingServiceOrExportExemption() {
        Long category=createCategory("仅资产展示");var rule=ruleSave(category);rule.setApplicationScene(List.of("1"));rule.setStatus((short)0);workbench.create(rule);
        var req=fieldRequest(2L,Map.of("value",List.of(category)),Map.of("value","ABCD"));
        req.setApplicationScene("1");assertThat(fields.mask(req).getRows().getFirst().get("value")).isEqualTo("*BCD");
        req.setApplicationScene("2");assertThat(fields.mask(req).getRows().getFirst().get("value")).isEqualTo("ABCD");
        assertThat(nativeWhite.getCategoryExemptionsStrict(1L,"EXPORT",Set.of(category))).isEmpty();
    }

    private long ruleId(String source)throws Exception{return count("SELECT id FROM dh_governance_safety_rule WHERE source_record_id='"+source+"'");}
    private long createCategory(String name){var req=new SafetyCategorySaveReqVO();req.setName(name+UUID.randomUUID().toString().substring(0,6));req.setCategoryId(session.getMapper(CategoryDataMapper.class).selectList().stream().filter(v->"36".equals(v.getSourceRecordId())).findFirst().orElseThrow().getId());req.setDataLevelId(1);req.setPriority("LOWEST");req.setStatus((short)0);return categories.createCategory(req);}
    private static SafetyWhitelistWorkbenchSaveReqVO whiteSave(Long category){var req=new SafetyWhitelistWorkbenchSaveReqVO();req.setName("白名单"+UUID.randomUUID().toString().replace("-",""));req.setCategoryId(category);req.setUserIds(List.of(1L));req.setEffectiveCategory("1");req.setStartTime(LocalDate.now().minusDays(1));req.setEndTime(LocalDate.now().plusDays(1));req.setStatus((short)1);return req;}
    private SafetyRuleWorkbenchSaveReqVO ruleSave(Long category){var req=new SafetyRuleWorkbenchSaveReqVO();req.setCategoryId(category);req.setCategoryRevision(categoryMapper.selectById(category).getRevision());req.setRuleName("规则"+UUID.randomUUID());req.setStatus((short)1);req.setMaskType("2");req.setReplaceRule("1");req.setReplaceContent("*");req.setApplicationScene(List.of("1","2"));var range=new SafetyCategoryMaskReqVO.Interval();range.setStartNum(1);range.setEndNum(1);req.setIntervalList(List.of(range));return req;}
    private static SafetyRuleWorkbenchVersionReqVO ruleVersion(SafetyRuleWorkbenchRespVO row){var req=new SafetyRuleWorkbenchVersionReqVO();req.setId(row.getId());req.setCategoryRevision(row.getCategoryRevision());req.setRuleVersionId(row.getRuleVersionId());req.setRuleRevisionToken(row.getRuleRevisionToken());return req;}
    private static SafetyFieldMaskReqDTO fieldRequest(Long user,Map<String,List<Long>> facts,Map<String,Object> row){var req=new SafetyFieldMaskReqDTO();req.setUserId(user);req.setScene("QUERY");req.setRows(List.of(row));req.setFields(facts.entrySet().stream().map(v->{var field=new SafetyFieldMaskReqDTO.Field();field.setFieldName(v.getKey());field.setCategoryIds(v.getValue());return field;}).toList());return req;}
    private void inject(Object target, Map<String, Class<?>> fields) { fields.forEach((name, type) -> ReflectionTestUtils.setField(target, name, session.getMapper(type))); }
    @SuppressWarnings("unchecked") private <T> T proxy(Object target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
    private static void loginActor() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
    }
    private void sql(String text) throws Exception { try (var sql = connection.createStatement()) { sql.execute(text); } }
    private long count(String text) throws Exception { try (var sql = connection.createStatement(); var rows = sql.executeQuery(text)) { assertThat(rows.next()).isTrue(); return rows.getLong(1); } }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null && database.matches("mask_workbench_[a-f0-9]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, user, ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
