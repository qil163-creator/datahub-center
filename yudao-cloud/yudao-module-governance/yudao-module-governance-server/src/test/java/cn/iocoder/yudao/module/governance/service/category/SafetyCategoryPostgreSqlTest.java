package cn.iocoder.yudao.module.governance.service.category;

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
class SafetyCategoryPostgreSqlTest {
    private String controlUrl, database, user;
    private Connection connection;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private SafetyCategoryService categories;
    private CategoryDataService directories;
    private SafetyRuleService rules;
    private SafetyCategoryMaskService masks;
    private SafetyCategoryMapper categoryMapper;

    @BeforeAll void createOnlyOwnedDatabaseAndLoadBaseline() throws Exception {
        controlUrl = System.getenv("CLASSIFICATION_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres").doesNotContain(":5433/");
        user = System.getenv().getOrDefault("CLASSIFICATION_TEST_JDBC_USER", "catalog_test");
        database = "classification_api_" + UUID.randomUUID().toString().replace("-", "");
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
                SafetyReplaceItemMapper.class, SafetyFieldPermissionMapper.class, SafetyStaticTaskMapper.class)) config.addMapper(type);
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
    }

    @BeforeEach void login() { loginActor(); }
    @AfterEach void clearLogin() { SecurityContextHolder.clearContext(); }

    @Test void realImportedFactsKeepStableSensitiveReferencesAndConfiguredDisabledRules() throws Exception {
        assertThat(count("SELECT count(*) FROM dh_governance_safety_category WHERE source_record_id IS NOT NULL AND deleted=0")).isEqualTo(56);
        assertThat(count("SELECT count(*) FROM dh_governance_category_data WHERE source_record_id IS NOT NULL AND deleted=0")).isEqualTo(36);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule WHERE source_record_id IS NOT NULL AND deleted=0")).isEqualTo(52);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule WHERE source_record_id IS NOT NULL AND deleted=0 AND status=0")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM dh_asset_sensitive_list s JOIN dh_governance_safety_category c ON c.id=s.category_id WHERE s.deleted=0 AND c.deleted=0 AND c.rule_id IS NOT NULL")).isEqualTo(25);
        long categoryId = count("SELECT id FROM dh_governance_safety_category WHERE source_record_id='56'");
        var config = masks.get(categoryId);
        assertThat(config.getStatus()).isEqualTo((short) 1);
        assertThat(config.getIntervalList()).hasSize(1);
        assertThat(config.getIntervalList().getFirst().getStartNum()).isEqualTo(1);
        assertThat(rules.resolveUsableMaskingSpec(config.getRuleId())).isNull();
        assertThatThrownBy(() -> categories.deleteCategory(categoryId, config.getRevision())).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SAFETY_CATEGORY_REFERENCED.getCode());
        assertThat(count("SELECT count(*) FROM dh_asset_sensitive_list WHERE deleted=0")).isEqualTo(25);
    }

    @Test void newRuleJsonbRoundTripRemainsUsableAndUnchangedConfigDoesNotCreateSpuriousVersions() throws Exception {
        var request = ruleSave("新规则", Map.of("maskChar", "*", "intervals", List.of(Map.of("start", 2, "end", 2))));
        long id = rules.createRule(request); var spec = rules.resolveUsableMaskingSpec(id);
        assertThat(spec).isNotNull();
        assertThat(SafetyMaskEngine.mask("张三", spec.getMethodCode(), spec.getConfig(), spec.getReplaceItems())).isEqualTo("张*");
        request.setId(id); rules.updateRule(request);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + id)).isEqualTo(1);
        request.setConfig(Map.of("intervals", List.of(Map.of("end", 3, "start", 2)), "maskChar", "**"));
        rules.updateRule(request);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + id)).isEqualTo(2);
        assertThat(rules.resolveUsableMaskingSpec(id).getVersionNo()).isEqualTo(2);
    }

    @Test void maskSaveAndClassificationRevisionAreOneTransactionAndFailedConfigKeepsPriorVersion() throws Exception {
        long id = createCategory("配置事务");
        var request = maskSave(id, 1L); masks.save(request);
        var saved = masks.get(id);
        assertThat(saved.getRevision()).isEqualTo(2);
        assertThat(saved.getRuleName()).isEqualTo("展示规则");
        long ruleId = saved.getRuleId();
        request.setRevision(2L); request.setRuleVersionId(saved.getRuleVersionId()); request.setRuleRevisionToken(saved.getRuleRevisionToken()); request.getIntervalList().getFirst().setEndNum(1);
        assertThatThrownBy(() -> masks.save(request)).isInstanceOf(ServiceException.class);
        assertThat(masks.get(id).getRevision()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + ruleId)).isEqualTo(1);
        request.getIntervalList().getFirst().setEndNum(2); request.setRevision(1L);
        assertThatThrownBy(() -> masks.save(request)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode());
        request.setRevision(2L); request.getIntervalList().getFirst().setEndNum(3); request.setReplaceContent("**");
        masks.save(request);
        assertThat(masks.get(id).getRevision()).isEqualTo(3L);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + ruleId)).isEqualTo(2);
    }

    @Test void concurrentSameRevisionHasExactlyOneWinnerWithoutLosingSuccessfulWrites() throws Exception {
        long id = createCategory("并发状态");
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> write = () -> {
                loginActor(); start.await();
                try {
                    var request = new SafetyCategoryStatusReqVO(); request.setId(id); request.setRevision(1L); request.setStatus((short) 0);
                    categories.changeStatus(request); return true;
                } catch (ServiceException failure) {
                    assertThat(failure.getCode()).isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode()); return false;
                } finally { SecurityContextHolder.clearContext(); }
            };
            Future<Boolean> first = executor.submit(write), second = executor.submit(write); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(categories.getCategory(id).getRevision()).isEqualTo(2L);
    }

    @Test void independentRulePublishMakesAnOldClassificationMaskFormConflictInsideTheRuleLock() throws Exception {
        long id = createCategory("跨入口并发");
        var oldForm = maskSave(id, 1L); masks.save(oldForm);
        var saved = masks.get(id); oldForm.setRevision(saved.getRevision()); oldForm.setRuleVersionId(saved.getRuleVersionId()); oldForm.setRuleRevisionToken(saved.getRuleRevisionToken());
        var independent = ruleSave("规则页独立更新", Map.of("intervals", List.of(Map.of("start", 1, "end", 1)), "maskChar", "NEW"));
        independent.setId(saved.getRuleId()); rules.updateRule(independent);
        long newVersion = rules.getRule(saved.getRuleId()).getCurrentVerId();
        assertThat(newVersion).isNotEqualTo(saved.getRuleVersionId());
        assertThatThrownBy(() -> masks.save(oldForm)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode());
        assertThat(categories.getCategory(id).getRevision()).isEqualTo(saved.getRevision());
        assertThat(rules.getRule(saved.getRuleId()).getCurrentVerId()).isEqualTo(newVersion);
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + saved.getRuleId())).isEqualTo(2);
    }

    @Test void nativeDirectoryDeleteWritesActualActorAndTimeAndKeepsItsPhysicalRow() throws Exception {
        var request = new CategoryDataSaveReqVO(); request.setParentId(0L); request.setCategoryName("临时目录" + UUID.randomUUID().toString().substring(0, 6));
        long id = directories.createCategoryData(request);
        sql("UPDATE dh_governance_category_data SET updater='migration:old',update_time='2000-01-01' WHERE id=" + id);
        directories.deleteCategoryData(id, 1L);
        assertThat(count("SELECT count(*) FROM dh_governance_category_data WHERE id=" + id + " AND deleted=1 AND updater='9' AND update_time>'2000-01-02' AND revision=2")).isEqualTo(1);
        assertThat(directories.getCategoryData(id)).isNull();
    }

    @Test void metadataOnlyRuleUpdateAlsoInvalidatesTheClassificationFormWithoutCreatingAnotherVersion() throws Exception {
        long id = createCategory("元信息并发"); var oldForm = maskSave(id, 1L); masks.save(oldForm);
        var saved = masks.get(id); oldForm.setRevision(saved.getRevision()); oldForm.setRuleVersionId(saved.getRuleVersionId());
        oldForm.setRuleRevisionToken(saved.getRuleRevisionToken());
        var spec = rules.resolveUsableMaskingSpec(saved.getRuleId());
        var independent = ruleSave("刚修改的规则名称", spec.getConfig()); independent.setId(saved.getRuleId());
        rules.updateRule(independent);
        assertThat(rules.getRule(saved.getRuleId()).getCurrentVerId()).isEqualTo(saved.getRuleVersionId());
        assertThatThrownBy(() -> masks.save(oldForm)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SAFETY_CATEGORY_CONFLICT.getCode());
        assertThat(rules.getRule(saved.getRuleId()).getRuleName()).isEqualTo("刚修改的规则名称");
        assertThat(masks.get(id).getRuleRevisionToken()).isNotEqualTo(saved.getRuleRevisionToken());
        assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver WHERE rule_id=" + saved.getRuleId())).isEqualTo(1);
    }

    @Test void batchConflictRollsBackEveryLevelAndDeleteKeepsTheTombstone() throws Exception {
        long first = createCategory("批量甲"), second = createCategory("批量乙");
        var batch = new SafetyCategoryBatchLevelReqVO(); batch.setDataLevelId(2);
        batch.setItems(List.of(version(first, 1), version(second, 2)));
        assertThatThrownBy(() -> categories.assignLevel(batch)).isInstanceOf(ServiceException.class);
        assertThat(categories.getCategory(first).getDataLevelId()).isEqualTo(1);
        assertThat(categories.getCategory(first).getRevision()).isEqualTo(1L);
        batch.setItems(List.of(version(first, 1), version(second, 1))); categories.assignLevel(batch);
        assertThat(categories.getCategory(second).getDataLevelId()).isEqualTo(2);
        categories.deleteCategory(first, 2L);
        assertThat(count("SELECT deleted FROM dh_governance_safety_category WHERE id=" + first)).isEqualTo(1);
        assertThat(categoryMapper.selectById(first)).isNull();
    }

    @Test void definitionUpdateKeepsInvisibleSortRemarkAndClearsOnlySourceUpdaterDisplay() throws Exception {
        long id = createCategory("保留域属性");
        sql("UPDATE dh_governance_safety_category SET sort=77,remark='保留',source_creator_name='来源创建者',source_updater_name='来源修改者',source_create_time='2026-07-29',source_update_time='2026-07-30' WHERE id=" + id);
        var request = categorySave("编辑后"); request.setId(id); request.setRevision(1L);
        categories.updateCategory(request); var saved = categories.getCategory(id);
        assertThat(saved.getSort()).isEqualTo(77); assertThat(saved.getRemark()).isEqualTo("保留");
        assertThat(saved.getSourceCreatorName()).isEqualTo("来源创建者"); assertThat(saved.getSourceCreateTime()).isNotNull();
        assertThat(saved.getSourceUpdaterName()).isNull(); assertThat(saved.getSourceUpdateTime()).isNull();
        assertThat(saved.getUpdater()).isEqualTo("9");
    }

    @Test void staticRuleReferencesUseExactJsonbNumericOrStringIdentityAndPreserveDeletedStatusSemantics() throws Exception {
        sql("INSERT INTO dh_governance_safety_static_task(task_name,source_database_id,source_table_name,target_database_id,target_table_name,field_rules_json,status,deleted) VALUES "
                + "('数字',1,'x',1,'y','[{\"ruleId\":123}]',0,0),"
                + "('字符串',1,'x',1,'y','[{\"ruleId\":\"123\"}]',0,0),"
                + "('停用仍引用',1,'x',1,'y','[{\"ruleId\":123}]',1,0),"
                + "('不同编号',1,'x',1,'y','[{\"ruleId\":1234}]',0,0),"
                + "('普通文本',1,'x',1,'y','[{\"columnName\":\"ruleId:123\"}]',0,0),"
                + "('已删除',1,'x',1,'y','[{\"ruleId\":123}]',0,1),"
                + "('大编号',1,'x',1,'y','[{\"ruleId\":\"9007199254740993\"}]',0,0)");
        var tasks = session.getMapper(SafetyStaticTaskMapper.class);
        assertThat(tasks.countByRuleId(123L)).isEqualTo(3);
        assertThat(tasks.countByRuleId(1234L)).isEqualTo(1);
        assertThat(tasks.countByRuleId(9007199254740993L)).isEqualTo(1);
        assertThat(tasks.countByRuleId(12L)).isZero();
    }

    @Test void categoryWriteFailureRollsBackNewRuleAndPublishedVersionTogether() throws Exception {
        long id = createCategory("发布回滚");
        long ruleCount = count("SELECT count(*) FROM dh_governance_safety_rule");
        long versionCount = count("SELECT count(*) FROM dh_governance_safety_rule_ver");
        sql("CREATE FUNCTION reject_mask_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN "
                + "IF NEW.id=" + id + " AND NEW.rule_id IS DISTINCT FROM OLD.rule_id THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END$$;"
                + "CREATE TRIGGER reject_mask_fixture BEFORE UPDATE ON dh_governance_safety_category FOR EACH ROW EXECUTE FUNCTION reject_mask_fixture()");
        try {
            assertThatThrownBy(() -> masks.save(maskSave(id, 1L))).isInstanceOf(RuntimeException.class);
            assertThat(count("SELECT count(*) FROM dh_governance_safety_rule")).isEqualTo(ruleCount);
            assertThat(count("SELECT count(*) FROM dh_governance_safety_rule_ver")).isEqualTo(versionCount);
            assertThat(categories.getCategory(id).getRevision()).isEqualTo(1);
            assertThat(categories.getCategory(id).getRuleId()).isNull();
        } finally { sql("DROP TRIGGER reject_mask_fixture ON dh_governance_safety_category; DROP FUNCTION reject_mask_fixture()"); }
    }

    @Test void directoryDeleteChecksChildrenAndClassificationWhileRenamePreservesSourceIdentity() throws Exception {
        long directoryId = count("SELECT category_id FROM dh_governance_safety_category WHERE source_record_id='56'");
        var existing = directories.getCategoryData(directoryId);
        assertThatThrownBy(() -> directories.deleteCategoryData(directoryId, existing.getRevision())).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SAFETY_DIRECTORY_REFERENCED.getCode());
        var request = new CategoryDataSaveReqVO(); request.setId(directoryId); request.setRevision(existing.getRevision());
        request.setCategoryName("日志审计更新"); request.setParentId(existing.getParentId()); directories.updateCategoryData(request);
        assertThat(directories.getCategoryData(directoryId).getCategoryCode()).isEqualTo(existing.getCategoryCode());
        assertThat(directories.getCategoryData(directoryId).getRevision()).isEqualTo(existing.getRevision() + 1);
    }

    @Test void mapperFiltersBeforePaginationAndSourceIdentifiersSortNumericallyWithoutPrecisionLoss() throws Exception {
        long first = createCategory("列表排序甲"), second = createCategory("列表排序乙");
        sql("UPDATE dh_governance_safety_category SET short_name='PG_ORDER_ONLY',source_record_id='9007199254740994' WHERE id=" + first);
        sql("UPDATE dh_governance_safety_category SET short_name='PG_ORDER_ONLY',source_record_id='9007199254740993' WHERE id=" + second);
        try {
            var request = new SafetyCategoryPageReqVO(); request.setShortName("PG_ORDER_ONLY"); request.setPageSize(1);
            request.setSortField("id"); request.setSortOrder("asc");
            request.setCategoryId(categories.getCategory(first).getCategoryId());
            var page = categories.getCategoryPage(request);
            assertThat(page.getTotal()).isEqualTo(2); assertThat(page.getList()).extracting(row -> row.getId()).containsExactly(second);
            request.setPageNo(2); assertThat(categories.getCategoryPage(request).getList()).extracting(row -> row.getId()).containsExactly(first);
            request.setPageNo(1); request.setSortField("createTime");
            assertThat(categories.getCategoryPage(request).getTotal()).isEqualTo(2);
            request.setName("' OR 1=1 --"); assertThat(categories.getCategoryPage(request).getTotal()).isZero();
        } finally {
            sql("UPDATE dh_governance_safety_category SET source_record_id=NULL WHERE id IN (" + first + "," + second + ")");
        }
    }

    private long createCategory(String name) { return categories.createCategory(categorySave(name + UUID.randomUUID().toString().substring(0, 6))); }
    private SafetyCategorySaveReqVO categorySave(String name) {
        var request = new SafetyCategorySaveReqVO();
        request.setCategoryId(session.getMapper(CategoryDataMapper.class).selectList().stream()
                .filter(row -> "36".equals(row.getSourceRecordId())).findFirst().orElseThrow().getId());
        request.setName(name); request.setDataLevelId(1); request.setPriority("LOWEST"); request.setStatus((short) 1);
        return request;
    }
    private static SafetyCategoryVersionReqVO version(long id, long revision) {
        var request = new SafetyCategoryVersionReqVO(); request.setId(id); request.setRevision(revision); return request;
    }
    private static SafetyRuleSaveReqVO ruleSave(String name, Map<String, Object> config) {
        var request = new SafetyRuleSaveReqVO(); request.setRuleName(name); request.setMethodCode("MASK"); request.setStatus((short) 0);
        request.setSort(0); request.setConfig(config); return request;
    }
    private static SafetyCategoryMaskReqVO maskSave(long id, long revision) {
        var request = new SafetyCategoryMaskReqVO(); request.setId(id); request.setRevision(revision); request.setRuleName("展示规则");
        request.setApplicationScene(List.of("1", "2")); request.setMaskType("2"); request.setReplaceRule("1");
        request.setReplaceContent("*"); request.setStatus((short) 0);
        var range = new SafetyCategoryMaskReqVO.Interval(); range.setStartNum(2); range.setEndNum(2); request.setIntervalList(List.of(range)); return request;
    }
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
        if (database != null && database.matches("classification_api_[a-f0-9]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, user, ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
