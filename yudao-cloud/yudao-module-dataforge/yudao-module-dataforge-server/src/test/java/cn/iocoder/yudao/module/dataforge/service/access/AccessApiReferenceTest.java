package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessApiReferenceTest {
    private AccessRunAuthorization authorization() {
        var authorization = new AccessRunAuthorization();
        ReflectionTestUtils.setField(authorization, "access", mock(ExecutionAccessService.class));
        ObjectProvider<DataAccessExecutor> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(DataAccessExecutor.class));
        ReflectionTestUtils.setField(authorization, "executor", provider);
        return authorization;
    }
    private DataAccessDO snapshot() {
        return DataAccessDO.builder().engineType(3).targetDatabaseId(20L)
                .apiAccessJson("{\"mappings\":[{\"apiConfig\":{\"apiName\":\"独立接口\",\"requestMethod\":\"GET\",\"requestUrl\":\"https://example.invalid/original\"},\"targetTable\":\"output\"}]}").build();
    }
    @Test void inlineConfigurationRemainsFrozenWithoutApiResourceLookups() {
        var definition = snapshot(); var authorization = authorization();
        authorization.execution(definition, 99L);
        String frozen = AccessRunSnapshot.freeze(definition);
        definition.setApiAccessJson("{}");
        var restored = AccessRunSnapshot.restore(frozen);
        assertDoesNotThrow(() -> authorization.execution(restored, 99L));
        assertTrue(restored.getApiAccessJson().contains("https://example.invalid/original"));
        assertFalse(restored.getApiAccessJson().contains("sourceDefinitionHashes"));
        assertFalse(restored.getApiAccessJson().contains("sourceId"));
    }
    @Test void missingInlineConfigurationCannotExecute() {
        var definition = snapshot();
        var config = JSONUtil.parseObj(definition.getApiAccessJson());
        config.getJSONArray("mappings").getJSONObject(0).remove("apiConfig");
        definition.setApiAccessJson(config.toString());
        assertThrows(RuntimeException.class, () -> authorization().execution(definition, 99L));
    }
}
