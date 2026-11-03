package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDistributeApiPolicyTest {
    private final MasterDistributeApiPolicy policy = new MasterDistributeApiPolicy();
    private final MasterColumnMapper columns = mock(MasterColumnMapper.class);
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final StandardCodeSetMapper sets = mock(StandardCodeSetMapper.class);
    private final StandardCodeItemMapper items = mock(StandardCodeItemMapper.class);
    private MasterDistributeConfigVO.ApiConfig config;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(policy, "columns", columns);
        ReflectionTestUtils.setField(policy, "elements", elements);
        ReflectionTestUtils.setField(policy, "codeSets", sets);
        ReflectionTestUtils.setField(policy, "codeItems", items);
        config = new MasterDistributeConfigVO.ApiConfig(); config.setUrl("https://consumer.invalid/receive");
        when(columns.selectListByModelId(1L)).thenReturn(List.of(column("id",1),column("active",0),column("amount",0)));
    }
    private MasterColumnDO column(String name, int pk) {var c=new MasterColumnDO();c.setName(name);c.setPk(pk);return c;}
    private MasterDistributeConfigVO.FieldMapping mapping(String source, String target) {
        var m=new MasterDistributeConfigVO.FieldMapping();m.setSourceField(source);m.setTargetField(target);m.setEnabled(true);return m;
    }
    private MasterDistributeConfigVO.KeyValue pair(String key,String value) {
        var p=new MasterDistributeConfigVO.KeyValue();p.setKey(key);p.setValue(value);return p;
    }

    @Test void enabledScopeAndIncrementalRequireExplicitRealModelColumns() {
        config.setPushScope("ENABLED");config.setPushMode("INCREMENTAL");
        assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
        config.setStatusField("imaginary");assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
        config.setStatusField("active");var plan=policy.plan(1L,config);
        assertTrue(plan.includes(Map.of("active",1)));assertFalse(plan.includes(Map.of("active",0)));
        assertFalse(plan.includes(Map.of("id",1)));
        when(columns.selectListByModelId(1L)).thenReturn(List.of(column("active",0)));
        assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
    }

    @Test void mappedValuesPreserveExactNumbersNullDefaultsAndStableIdentity() {
        var amount=mapping("amount","decimal_value");amount.setDefaultValue("0.0000");
        var disabled=mapping("active","ignored");disabled.setEnabled(false);
        config.setMappings(List.of(amount,mapping("id","identity"),disabled));
        var plan=policy.plan(1L,config);
        var value=new BigDecimal("12345678901234567890.12345678901234567890");
        var row=Map.<String,Object>of("id","9007199254740999","amount",value,"active",1);
        var result=plan.map(row);
        assertSame(value,result.get("decimal_value"));assertEquals("9007199254740999",result.get("identity"));assertFalse(result.containsKey("ignored"));
        assertEquals("0.0000",plan.map(Map.of("id",1)).get("decimal_value"));
        assertEquals(plan.key(row),plan.key(new HashMap<>(row)));assertNotEquals(plan.key(row),plan.key(Map.of("id",2)));
        assertThrows(IllegalArgumentException.class,()->plan.key(Map.of("amount",value)));
        config.setMappings(List.of(mapping("amount","same"),mapping("id","same")));
        assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
    }

    @Test void standardDictionaryUsesPublishedElementAndEnabledEffectiveItems() {
        var c=column("active",0);c.setStandardElementId(10L);when(columns.selectListByModelId(1L)).thenReturn(List.of(c));
        var m=mapping("active","active_name");m.setTransform("STANDARD_DICT");config.setMappings(List.of(m));
        assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
        var element=new StandardElementDO();element.setCodeSetId(20);element.setLifecycleStatus("PUBLISHED");when(elements.selectById(10L)).thenReturn(element);
        var set=new StandardCodeSetDO();set.setId(20);set.setStatus((short)0);when(sets.selectById(20)).thenReturn(set);
        var item=new StandardCodeItemDO();item.setValue("1");item.setLabel("启用");when(items.selectTree(eq(20),any(),eq(false))).thenReturn(List.of(item));
        var plan=policy.plan(1L,config);assertEquals("启用",plan.map(Map.of("active",1)).get("active_name"));
        assertThrows(IllegalArgumentException.class,()->plan.map(Map.of("active",2)));
        set.setStatus((short)1);assertThrows(IllegalArgumentException.class,()->policy.plan(1L,config));
    }

    @Test void storedPublicConfigAndResponseNeverContainSecretValuesAndBlankUpdatePreservesThem() {
        config.setAuthType("TOKEN");config.setToken("fixture-token");config.setHeaders(List.of(pair("X-Key","fixture-header")));
        config.setParams(List.of(pair("secret","fixture-param")));
        var task=new MasterDistributeDO();MasterDistributeApiPolicy.separateSecrets(task,config);
        assertFalse(JsonUtils.toJsonString(config).contains("fixture-"));assertFalse(task.toString().contains("fixture-"));
        MasterDistributeApiPolicy.redact(task,config);assertTrue(config.getTokenConfigured());assertNull(config.getToken());
        assertNull(config.getHeaders().getFirst().getValue());
        config.setToken("");MasterDistributeApiPolicy.restoreSecrets(task,config);
        assertEquals("fixture-token",config.getToken());assertEquals("fixture-header",config.getHeaders().getFirst().getValue());
        assertEquals("fixture-param",config.getParams().getFirst().getValue());assertFalse(config.toString().contains("fixture-"));
        config.setHeaders(List.of());MasterDistributeApiPolicy.restoreSecrets(task,config);assertTrue(config.getHeaders().isEmpty());
    }

    @Test void putTimeoutAndBearerAreRealRequestOptionsAndGetIsRejected() {
        config.setMethod("PUT");config.setTimeoutSeconds(47);config.setAuthType("TOKEN");config.setToken("fixture-token");
        var request=MasterDistributeExecutor.prepareApi(config).request(new byte[]{'[',']'});
        assertEquals("PUT",request.method());assertEquals(Duration.ofSeconds(47),request.timeout().orElseThrow());
        assertEquals("Bearer fixture-token",request.headers().firstValue("Authorization").orElseThrow());
        config.setMethod("GET");assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(config));
    }

    @Test void createAndUpdatePermissionsCannotImplicitlyEnableOrReconfigureAnActivePush() {
        var service=new MasterDistributeServiceImpl();var security=mock(cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService.class);
        var coordinator=mock(MasterDistributeRunCoordinator.class);ReflectionTestUtils.setField(service,"securityFrameworkService",security);ReflectionTestUtils.setField(service,"coordinator",coordinator);
        var req=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO();req.setId(10L);req.setDistributeType(2);req.setStatus(1);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.createDistribute(req));
        var task=MasterDistributeDO.builder().id(10L).distributeType(2).status(1).build();
        when(coordinator.withIdleTask(eq(10L),any())).thenAnswer(call->{java.util.function.Function<MasterDistributeDO,Object> action=call.getArgument(1);return action.apply(task);});
        req.setStatus(null);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.updateDistribute(req));
        task.setStatus(0);req.setStatus(1);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.updateDistribute(req));
    }
}
