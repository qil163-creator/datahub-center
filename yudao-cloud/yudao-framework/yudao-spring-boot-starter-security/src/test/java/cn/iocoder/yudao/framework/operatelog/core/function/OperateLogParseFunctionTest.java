package cn.iocoder.yudao.framework.operatelog.core.function;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.operatelog.config.YudaoOperateLogConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.mzt.logapi.starter.annotation.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;
import java.util.function.BiConsumer;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;
import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.via;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作日志状态码 / 编号翻译函数的测试：直接调用与经 {@code @LogRecord} 模板渲染两种方式。
 */
class OperateLogParseFunctionTest {

    // ========== 直接调用 ==========

    @Test
    void statusFunctionsTranslateKnownCodesAndKeepUnknownValues() {
        var common = new CodeLabelParseFunction(CodeLabelParseFunction.COMMON_STATUS, Map.of("0", "启用", "1", "停用"));
        assertEquals("启用", common.apply(0));
        assertEquals("停用", common.apply((short) 1));
        assertEquals("停用", common.apply("1"));
        assertEquals("未设置", common.apply(null));
        assertEquals("未知", common.apply("未知"));

        var online = CodeLabelParseFunction.ofPositive(CodeLabelParseFunction.ONLINE_STATUS, "上线", "下线");
        assertEquals("上线", online.apply(1));
        assertEquals("下线", online.apply(false));
        assertEquals(CodeLabelParseFunction.ONLINE_STATUS, online.functionName());
        assertFalse(online.executeBefore());
    }

    @Test
    void entityNamesSupportSingleIdsCollectionsAndIdText() {
        var names = Map.of(1L, "甲", 2L, "乙");
        var function = new EntityNameParseFunction("getFixtureName", ids -> filter(names, ids));
        assertTrue(function.executeBefore());
        assertEquals("甲", function.apply(1L));
        assertEquals("甲", function.apply(1));
        assertEquals("甲、乙", function.apply(List.of(1L, 2L)));
        assertEquals("甲、乙", function.apply("[1, 2]"));
        assertEquals("甲、编号9", function.apply(List.of(1L, 9L)));
        assertEquals("无", function.apply(0L));
        assertEquals("未设置", function.apply(null));
        assertEquals("未设置", function.apply(List.of()));
        // 非编号文本原样输出，不把文字里的数字当成编号
        assertEquals("全部未提交", function.apply("全部未提交"));
        assertEquals("版本2", function.apply("版本2"));
    }

    @Test
    void loaderFailureFallsBackToIdsWithoutThrowing() {
        var function = new EntityNameParseFunction("getFixtureName", ids -> {
            throw new IllegalStateException("fixture");
        });
        assertEquals("编号3、编号4", function.apply(List.of(3L, 4L)));
    }

    @Test
    void loaderAndViaHelpersResolveOwnerNamesAndAcceptIntegerIds() {
        record Owner(Integer id, String name) { }
        record Child(Long id, Integer ownerId) { }
        var owners = List.of(new Owner(7, "所属对象"));
        var children = List.of(new Child(11L, 7), new Child(12L, null));
        var ownerLoader = loader(ids -> owners.stream().filter(o -> ids.contains(o.id().longValue())).toList(),
                Owner::id, Owner::name);
        var function = new EntityNameParseFunction("getChildName", via(
                ids -> children.stream().filter(c -> ids.contains(c.id())).toList(),
                Child::id, Child::ownerId, ownerLoader));
        assertEquals("所属对象、编号12", function.apply(List.of(11L, 12L)));
    }

    // ========== 经 @LogRecord 模板渲染 ==========

    @BeforeEach
    void login() {
        var request = new MockHttpServletRequest("POST", "/admin-api/fixture/status");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L).setUserType(2), request);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void templatesRenderTranslatedStatusesFromTheAutoConfiguration() {
        run((fixture, config) -> {
            fixture.changeStatus(1L, 0, 1);
            assertEquals("将对象【甲】的状态从【启用】改为【停用】", config.records.getFirst().getAction());
        });
    }

    @Test
    void deleteTemplateReadsTheNameBeforeTheObjectIsRemoved() {
        run((fixture, config) -> {
            fixture.delete(List.of(1L, 2L));
            assertEquals("批量删除了对象【甲、乙】", config.records.getFirst().getAction());
            assertFalse(config.store.containsKey(1L));
        });
    }

    private void run(BiConsumer<Fixture, FixtureConfiguration> check) {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoOperateLogConfiguration.class))
                .withUserConfiguration(FixtureConfiguration.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    check.accept(context.getBean(Fixture.class), context.getBean(FixtureConfiguration.class));
                });
    }

    private static Map<Long, String> filter(Map<Long, String> names, Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        ids.forEach(id -> {
            if (names.containsKey(id)) {
                result.put(id, names.get(id));
            }
        });
        return result;
    }

    public static class Fixture {

        private final Map<Long, String> store;

        Fixture(Map<Long, String> store) {
            this.store = store;
        }

        @LogRecord(type = "测试对象", subType = "修改状态", bizNo = "{{#id}}",
                success = "将对象【{getFixtureName{#id}}】的状态从【{getCommonStatus{#before}}】改为【{getCommonStatus{#after}}】",
                extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> changeStatus(Long id, Integer before, Integer after) {
            return CommonResult.success(true);
        }

        @LogRecord(type = "测试对象", subType = "批量删除", bizNo = "{{#ids}}",
                success = "批量删除了对象【{getFixtureName{#ids}}】",
                extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> delete(List<Long> ids) {
            ids.forEach(store::remove);
            return CommonResult.success(true);
        }

    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class FixtureConfiguration {

        final List<OperateLogCreateReqDTO> records = new ArrayList<>();
        final Map<Long, String> store = new HashMap<>(Map.of(1L, "甲", 2L, "乙"));

        @Bean
        OperateLogCommonApi operateLogApi() {
            return request -> {
                records.add(request);
                return CommonResult.success(true);
            };
        }

        @Bean
        EntityNameParseFunction fixtureNameParseFunction() {
            return new EntityNameParseFunction("getFixtureName", ids -> filter(store, ids));
        }

        @Bean
        Fixture fixture() {
            return new Fixture(store);
        }

    }

}
