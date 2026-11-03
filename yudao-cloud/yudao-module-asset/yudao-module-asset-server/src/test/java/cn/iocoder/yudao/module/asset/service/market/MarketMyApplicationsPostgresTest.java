package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketMyApplicationsPostgresTest extends SharingPostgresSupport {
    private MarketResourceApplyMapper mapper;

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("my-applications", new SpringManagedTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center", jdbcUser, "")));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config))
                .getMapper(MarketResourceApplyMapper.class);
        sql("""
                INSERT INTO dh_market_resource_apply(id,apply_user_id,status,source_work_order_id,deleted)
                VALUES (1,9,0,NULL,0),(2,9,1,NULL,0),(3,9,3,NULL,0),(4,9,2,NULL,0),
                       (5,10,1,NULL,0),(6,9,1,100,0),(7,9,1,NULL,1)
                """);
    }

    @Test
    void pageReturnsAllOwnLocalStatesButNeverOtherUsersMigratedOrDeletedRows() {
        var req = new MarketApplyPageReqVO();
        req.setPageNo(1); req.setPageSize(2); req.setIfSelf(0); req.setIfOwner(1);
        var first = mapper.selectPage(req, 9L, true, false, List.of());
        assertThat(first.getTotal()).isEqualTo(4);
        assertThat(first.getList()).extracting("id").containsExactly(4L, 3L);
        req.setPageNo(2);
        var second = mapper.selectPage(req, 9L, true, false, List.of());
        assertThat(second.getList()).extracting("id").containsExactly(2L, 1L);
        req.setPageNo(1); req.setStatus(3);
        var rejected = mapper.selectPage(req, 9L, true, false, List.of());
        assertThat(rejected.getTotal()).isEqualTo(1);
        assertThat(rejected.getList()).extracting("id").containsExactly(3L);
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("缺少测试夹具 " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
