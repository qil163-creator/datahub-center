package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper;
import cn.iocoder.yudao.module.governance.service.standardcodeset.StandardCodeMappingService;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeMappingSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.service.qualityrule.AuditRuleDimensionServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL：JSONB 往返、部分唯一索引、引用删除及父行锁竞态。 */
@Timeout(40)
class StandardElementWorkbenchPostgreSqlTest {
    private final String schema = "standard_workbench_" + UUID.randomUUID().toString().replace("-", "");
    private String url, user, password;
    private boolean created;
    private StandardElementRuleMapper rules;
    private StandardElementRuleService service;
    private StandardCodeMappingService mappingService;
    private TransactionTemplate transactions;

    @BeforeEach void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "需要显式隔离 PostgreSQL 地址");
        assertTrue(url.matches("jdbc:postgresql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+"));
        user = System.getProperty("datahub.test.postgres.user", "postgres"); password = System.getProperty("datahub.test.postgres.password", "");
        try (var c = DriverManager.getConnection(url,user,password)) {
            sql(c,"CREATE SCHEMA " + schema); created=true;sql(c,"SET search_path TO " + schema);
            for(String name:List.of("standard_element","standard_code_set","standard_code_item","standard_doc","category_datameta","audit_rule","clean_rule","category_clean_rule","audit_rule_dimension")) {
                sql(c,"CREATE TABLE dh_governance_"+name+" (LIKE \"datahub-center\".dh_governance_"+name+" INCLUDING ALL)");
            }
            Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("sql/postgresql/migrations")))root=root.getParent();
            String migration=Files.readString(root.resolve("sql/postgresql/migrations/0191_governance_standard-element-workbench.sql"));
            // 菜单由完整迁移验证覆盖；此处只在随机测试模式执行结构与引用约束。
            sql(c,"INSERT INTO dh_governance_audit_rule SELECT * FROM \"datahub-center\".dh_governance_audit_rule");
            sql(c,"INSERT INTO dh_governance_audit_rule_dimension SELECT * FROM \"datahub-center\".dh_governance_audit_rule_dimension");
            sql(c,"INSERT INTO dh_governance_category_clean_rule SELECT * FROM \"datahub-center\".dh_governance_category_clean_rule");
            sql(c,"INSERT INTO dh_governance_clean_rule SELECT * FROM \"datahub-center\".dh_governance_clean_rule");
            sql(c,migration.substring(migration.indexOf("DO $types$"),migration.indexOf("-- 移动既有页面")));
            sql(c,"INSERT INTO dh_governance_standard_element(id,code,name,eng_name,status,sort,lifecycle_status) VALUES(1001,'DE_TEST','测试数据元','amount',0,0,'DRAFT')");
            sql(c,"INSERT INTO dh_governance_standard_code_set(id,name,type,status) VALUES(1001,'测试码表','STD_TEST',0),(1002,'其他码表','STD_OTHER',0)");
            sql(c,"INSERT INTO dh_governance_standard_code_item(id,label,value,code_set_id,dict_type,sort,status) VALUES(1001,'正常','A',1001,'STD_TEST',0,0),(1002,'其他','B',1002,'STD_OTHER',0,0)");
        }
        var source = new UnpooledDataSource("org.postgresql.Driver",url+"?currentSchema="+schema+"&connectTimeout=5&socketTimeout=10",user,password);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setEnvironment(new Environment("standard-workbench",new SpringManagedTransactionFactory(),source));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        for(Class<?> mapper:List.of(StandardElementRuleMapper.class,StandardElementMapper.class,AuditRuleMapper.class,CleanRuleMapper.class,StandardCodeItemMapper.class,StandardCodeSetMapper.class,StandardCodeMappingMapper.class,CategoryCleanRuleMapper.class,AuditRuleDimensionMapper.class))config.addMapper(mapper);
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));rules=sessions.getMapper(StandardElementRuleMapper.class);
        var runtime=mock(DbSwitchRuntime.class);when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        var dimensions = new AuditRuleDimensionServiceImpl();
        ReflectionTestUtils.setField(dimensions, "auditRuleDimensionMapper", sessions.getMapper(AuditRuleDimensionMapper.class));
        service=new StandardElementRuleService(rules,sessions.getMapper(StandardElementMapper.class),sessions.getMapper(AuditRuleMapper.class),sessions.getMapper(CleanRuleMapper.class),
                sessions.getMapper(CategoryCleanRuleMapper.class),dimensions,new AuditSqlRegistry(),new CleanFunctionRegistry(),runtime,
                mock(StandardCatalogService.class),mock(ModelRuleContextApi.class),mock(SecurityFrameworkService.class),sessions.getMapper(StandardCodeItemMapper.class),sessions.getMapper(StandardCodeSetMapper.class));
        mappingService = new StandardCodeMappingService(sessions.getMapper(StandardCodeMappingMapper.class), sessions.getMapper(StandardCodeSetMapper.class),
                sessions.getMapper(StandardCodeItemMapper.class), mock(StandardCatalogService.class));
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() throws Exception {
        if(!created)return;assertTrue(schema.matches("standard_workbench_[a-f0-9]{32}"));
        try(var c=DriverManager.getConnection(url,user,password)){sql(c,"DROP SCHEMA "+schema+" CASCADE");}
    }

    @Test void catalogShowsAllTemplatesAndEnablesTheNineSupportedStandardRules() {
        var templates = service.templates("AUDIT");
        assertEquals(28, templates.size());
        assertEquals(Set.of("107","306","205","115","118","108","110","111","112"), templates.stream()
                .filter(StandardElementRuleTemplateRespVO::isEnabled).map(StandardElementRuleTemplateRespVO::getCode).collect(java.util.stream.Collectors.toSet()));
        var clean = service.templates("CLEAN");
        assertEquals(40, clean.size());
        assertEquals(Map.of("准确性修正",13L,"完整性修复",6L,"一致性修正",5L,"唯一性维护",5L,"有效性处理",6L,"及时性调整",5L),
                clean.stream().collect(java.util.stream.Collectors.groupingBy(StandardElementRuleTemplateRespVO::getCategoryName, java.util.stream.Collectors.counting())));
        assertEquals(6, clean.stream().map(StandardElementRuleTemplateRespVO::getCategoryId).distinct().count());
        assertTrue(clean.stream().allMatch(row -> row.getCategoryId() != null));
    }

    @Test void realMapperSavesAndReloadsNestedJsonTwiceWithRevisionAndUniqueName() throws Exception {
        long template;
        try(var c=connection();var st=c.createStatement();var rs=st.executeQuery("SELECT id FROM dh_governance_audit_rule WHERE code='107' AND deleted=0")){assertTrue(rs.next());template=rs.getLong(1);}
        var request=new StandardElementRuleSaveReqVO();request.setElementId(1001);request.setKind("AUDIT");request.setTemplateId(template);request.setName("数字校验");request.setStatus(0);
        request.setConfig(Map.of("allowedTypes",List.of("digit"),"useRegex",false,"ignoreNullValue",true));
        Long id=transactions.execute(status->service.create(request));
        var first=rules.selectById(id);assertTrue(first.getConfigJson().contains("digit"));assertEquals(0L,first.getRevision());
        request.setId(id);request.setRevision(0L);request.setConfig(Map.of("useRegex",true,"regex","^[0-9]{2}$","ignoreNullValue",false));
        transactions.executeWithoutResult(status->service.update(request));
        var updated=rules.selectById(id);assertEquals(1L,updated.getRevision());assertTrue(updated.getConfigJson().contains("^[0-9]{2}$"));
        assertThrows(RuntimeException.class,()->transactions.executeWithoutResult(status->service.update(request)));
        try(var c=connection()) {
            assertThrows(SQLException.class,()->sql(c,"INSERT INTO dh_governance_standard_element_rule(element_id,kind,name,template_id) VALUES(1001,'AUDIT','数字校验',"+template+")"));
        }
        assertEquals(1,rules.selectByOwner(1001).size());
    }

    @Test void mappingRejectsForeignCodeItemAndMappedItemDeletionAndPreservesUniqueOriginalValue() throws Exception {
        try(var c=connection()) {
            assertThrows(SQLException.class,()->sql(c,"INSERT INTO dh_governance_standard_code_value_mapping(code_set_id,original_value,code_item_id) VALUES(1001,'x',1002)"));
            sql(c,"INSERT INTO dh_governance_standard_code_value_mapping(code_set_id,original_value,code_item_id) VALUES(1001,'x',1001)");
            assertThrows(SQLException.class,()->sql(c,"INSERT INTO dh_governance_standard_code_value_mapping(code_set_id,original_value,code_item_id) VALUES(1001,'x',1001)"));
            assertThrows(SQLException.class,()->sql(c,"UPDATE dh_governance_standard_code_item SET deleted=1 WHERE id=1001"));
            sql(c,"UPDATE dh_governance_standard_code_value_mapping SET deleted=1 WHERE code_set_id=1001");
            sql(c,"UPDATE dh_governance_standard_code_item SET deleted=1 WHERE id=1001");
            assertThrows(SQLException.class,()->sql(c,"INSERT INTO dh_governance_standard_code_value_mapping(code_set_id,original_value,code_item_id) VALUES(1001,'y',1001)"));
        }
    }

    @Test void mappingMapperRoundtripDerivesTargetValueAndParentRevisionTracksExistingCommands() throws Exception {
        var request = new StandardCodeMappingSaveReqVO(); request.setCodeSetId(1001); request.setCodeItemId(1001); request.setOriginalValue("raw");
        Long id = transactions.execute(status -> mappingService.create(request));
        var first = mappingService.get(1001, id); assertEquals("正常", first.getCodeName()); assertEquals("A", first.getCodeValue());
        assertEquals(0L, first.getRevision());
        request.setId(id); request.setRevision(0L); request.setOriginalValue("changed");
        transactions.executeWithoutResult(status -> mappingService.update(request));
        assertEquals("changed", mappingService.get(1001, id).getOriginalValue()); assertEquals(1L, mappingService.get(1001, id).getRevision());
        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> mappingService.update(request)));
        try (var c = connection()) {
            sql(c, "UPDATE dh_governance_standard_element SET status=1 WHERE id=1001");
            try(var st=c.createStatement();var rs=st.executeQuery("SELECT revision FROM dh_governance_standard_element WHERE id=1001")) {assertTrue(rs.next());assertEquals(1L,rs.getLong(1));}
        }
    }

    @Test void concurrentCodeSetDeleteCannotLeaveNewMappingBehind() throws Exception {
        try(var deleting=connection();var writing=connection();var executor=Executors.newSingleThreadExecutor()) {
            deleting.setAutoCommit(false);sql(deleting,"UPDATE dh_governance_standard_code_set SET deleted=1 WHERE id=1001");
            var pending=executor.submit(()->{try{sql(writing,"INSERT INTO dh_governance_standard_code_value_mapping(code_set_id,original_value,code_item_id) VALUES(1001,'racing',1001)");return false;}catch(Exception ex){return true;}});
            assertThrows(TimeoutException.class,()->pending.get(150,TimeUnit.MILLISECONDS));
            deleting.commit();assertTrue(pending.get(5,TimeUnit.SECONDS));
        }
    }

    private Connection connection() throws Exception {var c=DriverManager.getConnection(url,user,password);sql(c,"SET search_path TO "+schema);return c;}
    private static void sql(Connection c,String value) throws Exception {try(var st=c.createStatement()){st.setQueryTimeout(10);st.execute(value);}}
}
