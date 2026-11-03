package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseTypeStatusPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void statusUpdatePreservesOtherFieldsAndChecksExistence() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dh_dataforge_database_type (id bigint PRIMARY KEY, type_code varchar(64), "
                    + "type_name varchar(64), sort integer, status smallint, remark varchar(128), "
                    + "create_time timestamp, update_time timestamp, creator varchar(64), updater varchar(64), "
                    + "deleted smallint DEFAULT 0)");
            statement.execute("INSERT INTO dh_dataforge_database_type (id, type_code, type_name, sort, status, remark) "
                    + "VALUES (1, 'POSTGRESQL', '连接器', 17, 0, '保留')");
            var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
            config.setEnvironment(new Environment("database-type-status", new SpringManagedTransactionFactory(), source));
            config.addMapper(DatabaseTypeMapper.class);
            var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var service = new DatabaseTypeServiceImpl();
            ReflectionTestUtils.setField(service, "databaseTypeMapper", sessions.getMapper(DatabaseTypeMapper.class));
            var request = new DatabaseTypeStatusReqVO();
            request.setId(1L);
            request.setStatus((short) 1);

            service.updateDatabaseTypeStatus(request);
            try (var result = statement.executeQuery("SELECT type_code, type_name, sort, status, remark "
                    + "FROM dh_dataforge_database_type WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("POSTGRESQL", result.getString(1));
                assertEquals("连接器", result.getString(2));
                assertEquals(17, result.getInt(3));
                assertEquals(1, result.getInt(4));
                assertEquals("保留", result.getString(5));
            }
            service.updateDatabaseTypeStatus(request);
            request.setId(2L);
            assertThrows(ServiceException.class, () -> service.updateDatabaseTypeStatus(request));
        }
    }
}
