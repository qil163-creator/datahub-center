package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTablePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离最小表与生产 Mapper 验证 PostgreSQL 空值筛选，不依赖已初始化的平台库。 */
@EnabledIfEnvironmentVariable(named = "METADATA_WRITE_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-zA-Z0-9_]+")
@Timeout(30)
class MetaTableSourceFilterPostgreSqlTest {
    private JdbcTemplate control;
    private String schema;
    private MetaTableMapper mapper;

    @BeforeEach
    void setup() {
        String url = System.getenv("METADATA_WRITE_TEST_JDBC_URL");
        String user = System.getenv("METADATA_WRITE_TEST_JDBC_USER");
        String password = System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_PASSWORD", "");
        control = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "meta_source_filter_" + UUID.randomUUID().toString().replace("-", "");
        control.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, user, password);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("metadata-source-filter", new SpringManagedTransactionFactory(), source));
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.addMapper(MetaTableMapper.class);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE dh_governance_meta_db(id bigint PRIMARY KEY, source_system_id bigint, deleted smallint DEFAULT 0)");
        // 补全结果映射所需列；该夹具只验证查询合同，不验证平台建表迁移。
        var columns = new ArrayList<String>();
        columns.add("id bigint PRIMARY KEY");
        Set<String> jsonColumns = Set.of("display_snapshot", "finalized_snapshot", "primary_keys", "indexes",
                "partition_info", "health_detail", "\"extend\"");
        for (var field : TableInfoHelper.getTableInfo(MetaTableDO.class).getFieldList()) {
            String type = field.getPropertyType() == Long.class ? "bigint"
                    : field.getPropertyType() == Integer.class ? "integer"
                    : field.getPropertyType() == LocalDateTime.class ? "timestamp"
                    : field.getPropertyType() == BigDecimal.class ? "numeric"
                    : field.getPropertyType() == Boolean.class ? "smallint DEFAULT 0"
                    : jsonColumns.contains(field.getColumn()) ? "jsonb" : "text";
            columns.add(field.getColumn() + " " + type);
        }
        jdbc.execute("CREATE TABLE dh_governance_meta_table(" + String.join(",", columns) + ")");
        jdbc.update("INSERT INTO dh_governance_meta_db(id,source_system_id,deleted) VALUES (1,NULL,0),(2,7,0),(3,NULL,1)");
        jdbc.update("INSERT INTO dh_governance_meta_table(id,db_id,datasource_id,table_name,object_status,publish_status,deleted) "
                + "VALUES (10,1,1,'unassigned_current',1,0,0),(11,2,2,'assigned_current',1,0,0),"
                + "(12,1,1,'unassigned_frozen',1,1,0),(13,1,1,'deleted_object',3,0,0),"
                + "(14,3,3,'deleted_database',1,0,0),(15,1,1,'logically_deleted',1,0,1)");
        jdbc.update("UPDATE dh_governance_meta_table SET published_snapshot_id=1000,finalized_snapshot='{}'::jsonb WHERE id=12");
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(MetaTableMapper.class);
    }

    @AfterEach
    void cleanup() {
        if (control != null && schema != null) control.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void virtualNullSourceIsDifferentFromRealSourceAndNoFilter() {
        var request = request();
        assertEquals(List.of(12L, 10L), ids(request));
        request.setSourceSystemId(7L);
        assertEquals(List.of(11L), ids(request));
        request.setSourceSystemId(null);
        assertEquals(List.of(14L, 12L, 11L, 10L), ids(request));
        request.setSourceSystemId(-2L);
        assertTrue(ids(request).isEmpty());
    }

    @Test
    void nullSourceCombinesWithFinalizationAndRealPagination() {
        var request = request();
        request.setFinalized(true);
        assertEquals(List.of(12L), ids(request));
        request.setFinalized(false);
        assertEquals(List.of(10L), ids(request));
        request.setFinalized(null);
        request.setPageSize(1);
        var first = mapper.selectPage(request);
        assertEquals(2L, first.getTotal());
        assertEquals(List.of(12L), first.getList().stream().map(MetaTableDO::getId).toList());
        request.setPageNo(2);
        assertEquals(List.of(10L), ids(request));
    }

    private static MetaTablePageReqVO request() {
        var request = new MetaTablePageReqVO();
        request.setSourceSystemId(MetaTablePageReqVO.UNASSIGNED_SOURCE_SYSTEM_ID);
        request.setPageSize(20);
        return request;
    }

    private List<Long> ids(MetaTablePageReqVO request) {
        return mapper.selectPage(request).getList().stream().map(MetaTableDO::getId).toList();
    }
}
