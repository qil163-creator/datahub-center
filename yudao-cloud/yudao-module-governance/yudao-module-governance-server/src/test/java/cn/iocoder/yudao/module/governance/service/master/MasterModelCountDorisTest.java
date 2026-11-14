package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实计数必须通过查询运行时的期限、只读会话和清理路径，不能只靠方言 mock 证明。 */
@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MasterModelCountDorisTest {
    private Connection control;
    private String schema;
    private DbSwitchRuntime runtime;
    private MasterModelServiceImpl service;
    private MasterModelDO model;

    @BeforeEach void createOwnedTable() throws Exception {
        String url = System.getProperty("datahub.test.doris.url");
        control = DriverManager.getConnection(url, "root", "");
        schema = "count_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE `" + schema + "`");
        sql("CREATE TABLE `" + schema + "`.`model_count`(id BIGINT) DUPLICATE KEY(id) "
                + "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("INSERT INTO `" + schema + "`.`model_count` VALUES (1),(2),(3)");
        var info = new MasterDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(20L); info.setProductType(ProductTypeEnum.DORIS); info.setSchema(schema);
        info.setJdbcUrl(url); info.setUsername("root"); info.setPassword("");
        info.setConfigurationRevision("native-count-fixture-1");
        var resolver = spy(new MasterDatabaseResolver());
        doReturn(info).when(resolver).resolve(eq(20L), any());
        model = MasterModelDO.builder().id(100L).databaseId(20L).tableName("model_count")
                .status(MasterModelDO.STATUS_RELEASED).publishState(MasterModelDO.PUBLISH_READY).build();
        var models = mock(MasterModelMapper.class);
        when(models.selectById(100L)).thenReturn(model);
        runtime = DbSwitchRuntime.standalone();
        service = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }

    @AfterEach void removeOwnedTable() throws Exception {
        SecurityContextHolder.clearContext();
        if (runtime != null) runtime.close();
        if (control == null) return;
        try (var owned = control) {
            if (schema != null) {
                assertTrue(schema.matches("count_[a-f0-9]{32}"));
                sql("DROP DATABASE IF EXISTS `" + schema + "` FORCE");
            }
        }
    }

    private void sql(String text) throws Exception {
        try (var statement = control.createStatement()) {
            statement.setQueryTimeout(10); statement.execute(text);
        }
    }

    @Test void boundedRuntimeCountsOnlyTheExplicitModelTableAndReportsMissingAsUnavailable() {
        var result = service.getDataCount(100L);
        assertTrue(result.isAvailable(), result.getMessage());
        assertEquals(3L, result.getDataCount());
        model.setTableName("missing_table");
        var missing = service.getDataCount(100L);
        assertFalse(missing.isAvailable()); assertNull(missing.getDataCount());
    }
}
