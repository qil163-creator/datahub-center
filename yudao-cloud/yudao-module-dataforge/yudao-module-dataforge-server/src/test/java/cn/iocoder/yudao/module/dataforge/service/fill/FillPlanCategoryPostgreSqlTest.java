package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在隔离 PostgreSQL 中验证迁移重放、分类事务锁、平台唯一性和计划分类清空。 */
@Testcontainers
class FillPlanCategoryPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0167_dataforge_fill-plan-category.sql";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }

    private Connection control;
    private AnnotationConfigApplicationContext app;
    private FillPlanCategoryService service;
    private FillPlanCategoryMapper categories;
    private FillPlanMapper plans;
    private TransactionTemplate transactions;

    @BeforeEach
    void setup() throws Exception {
        control = connection();
        execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        execute("CREATE SCHEMA \"datahub-center\"");
        execute("SET search_path TO \"datahub-center\", public");
        execute("CREATE SEQUENCE dh_dataforge_fill_plan_seq");
        String baseline = Files.readString(root().resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        var planDDL = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"dh_dataforge_fill_plan\" \\(.*?\n\\)\\s*;", Pattern.DOTALL)
                .matcher(baseline);
        assertTrue(planDDL.find());
        // 从新库基线构建升级前计划表；已有业务数据须在加分类后继续保留
        execute(planDDL.group().replace(",\n  \"category_id\" int8", ""));
        execute(Files.readString(root().resolve(MIGRATION)));

        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=datahub-center",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, "datahub-center");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("fill-plan-category-pg", new SpringManagedTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        var interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(FillPlanCategoryMapper.class);
        configuration.addMapper(FillPlanMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        app = new AnnotationConfigApplicationContext();
        app.register(Transactions.class);
        app.registerBean(PlatformTransactionManager.class, () -> manager);
        app.registerBean(FillPlanCategoryMapper.class, () -> sessions.getMapper(FillPlanCategoryMapper.class));
        app.registerBean(FillPlanMapper.class, () -> sessions.getMapper(FillPlanMapper.class));
        app.registerBean(FillPlanCategoryServiceImpl.class);
        app.refresh();
        service = app.getBean(FillPlanCategoryService.class);
        categories = app.getBean(FillPlanCategoryMapper.class);
        plans = app.getBean(FillPlanMapper.class);

    }

    @AfterEach
    void cleanup() throws Exception {

        if (app != null) app.close();
        if (control != null) control.close();
    }

    @Test
    void migrationIsRepeatableAndUniquenessAccountsForParentAndLogicalDelete() throws Exception {
        execute("INSERT INTO dh_dataforge_fill_plan(id,name,table_id,table_version_id,period_type,process_definition_key) VALUES (1,'存量计划',1,1,1,'approval')");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, "datahub-center");
        assertEquals("1", scalar("SELECT count(*) FROM dh_dataforge_fill_plan WHERE category_id IS NULL"));
        execute("INSERT INTO dh_dataforge_fill_plan_category(id,name) VALUES (10,'基础信息')");
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO dh_dataforge_fill_plan_category(id,name) VALUES (11,'基础信息')"));
        execute("INSERT INTO dh_dataforge_fill_plan_category(id,parent_id,name) VALUES (12,10,'基础信息'),(13,0,'其他分类')");
        execute("INSERT INTO dh_dataforge_fill_plan_category(id,name,deleted) VALUES (14,'基础信息',1)");
        assertEquals("4", scalar("SELECT count(*) FROM dh_dataforge_fill_plan_category"));
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO dh_dataforge_fill_plan_category(id,parent_id,name) VALUES (15,15,'自循环')"));
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO dh_dataforge_fill_plan_category(id,name,sort) VALUES (16,'排序越界',10000)"));

        // 单独执行新库新增分类结构，确保新安装包含同样的约束与唯一索引
        String baseline = Files.readString(root().resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        int start = baseline.indexOf("DROP TABLE IF EXISTS \"datahub-center\".\"dh_dataforge_fill_plan_category\"");
        int end = baseline.indexOf("-- 表结构： dh_dataforge_fill_plan\n", start);
        assertTrue(start > 0 && end > start);
        execute(baseline.substring(start, end));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, "datahub-center");
        assertEquals("0", scalar("SELECT count(*) FROM dh_dataforge_fill_plan_category"));
        execute("INSERT INTO dh_dataforge_fill_plan_category(id,name) VALUES (1,'新库分类')");
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO dh_dataforge_fill_plan_category(id,name) VALUES (2,'新库分类')"));
    }

    @Test
    void realMappersPreserveCategoryInvariantsAndClearNullWithoutAffectingPartialUpdates() throws Exception {
        Long parent = service.createCategory(request(null, 0L, "基础信息"));
        Long child = service.createCategory(request(null, parent, "学校"));
        assertEquals(FILL_PLAN_CATEGORY_PARENT_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> service.updateCategory(request(parent, child, "基础信息"))).getCode());
        assertEquals(FILL_PLAN_CATEGORY_HAS_CHILDREN.getCode(), assertThrows(ServiceException.class,
                () -> service.deleteCategory(parent)).getCode());
        assertThrows(IllegalTransactionStateException.class, () -> service.validateCategoryReference(child));

        execute("INSERT INTO dh_dataforge_fill_plan(id,name,table_id,table_version_id,period_type,process_definition_key,category_id) VALUES (100,'计划一',1,1,1,'approval'," + child + "),(101,'未分类计划',1,1,1,'approval',NULL),(102,'计划二',1,1,1,'approval'," + child + ")");
        var pageReq = new FillPlanPageReqVO();
        pageReq.setCategoryId(parent);
        var page = plans.selectPage(pageReq, service.getCategoryAndDescendantIds(parent));
        assertEquals(2L, page.getTotal());
        assertEquals(java.util.Set.of(100L, 102L), page.getList().stream().map(FillPlanDO::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(FILL_PLAN_CATEGORY_HAS_PLANS.getCode(), assertThrows(ServiceException.class,
                () -> service.deleteCategory(child)).getCode());

        assertEquals(2, service.getCategoryList().size());
        transactions.executeWithoutResult(status -> service.validateCategoryReference(child));
        assertEquals(FILL_PLAN_CATEGORY_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> transactions.executeWithoutResult(status -> service.validateCategoryReference(999L))).getCode());


        plans.updateById(FillPlanDO.builder().id(100L).status(1).build());
        assertEquals(child, plans.selectById(100L).getCategoryId());
        assertEquals(1, plans.updateCategoryId(102L, null));
        assertEquals(0, plans.updateCategoryId(999L, null));
        plans.updateCategoryId(100L, null);
        assertNull(plans.selectById(100L).getCategoryId());
        pageReq.setCategoryId(0L);
        assertEquals(3L, plans.selectPage(pageReq, (List<Long>) null).getTotal());
        service.deleteCategory(child);
        assertNull(categories.selectById(child));
    }

    @Test
    void categoryTransactionLockSerializesThePlatformAndReleasesAtCommit() throws Exception {
        String sql = FillPlanCategoryMapper.class.getMethod("lockCategoryTree")
                .getAnnotation(Select.class).value()[0];
        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try (var timeout = second.createStatement()) {
                timeout.execute("SET lock_timeout = '150ms'");
            }
            lock(first, sql);
            assertThrows(SQLException.class, () -> lock(second, sql));
            second.rollback();
            first.commit();
            lock(second, sql);
            second.commit();
        }
    }

    private static void lock(Connection connection, String sql) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            query.execute();
        }
    }

    private static FillPlanCategorySaveReqVO request(Long id, Long parentId, String name) {
        var request = new FillPlanCategorySaveReqVO();
        request.setId(id);
        request.setParentId(parentId);
        request.setName(name);
        return request;
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        return root;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = control.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = control.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

}
