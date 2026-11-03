package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlComponentVerifyReqVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EtlComponentVerificationServiceTest {

    private final EtlComponentVerificationService service = new EtlComponentVerificationService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "registry", new EtlExecutorRegistry());
    }

    @Test
    void verifiesQDataTableInputAliasAndNormalizesDataSourceId() {
        EtlComponentVerifyReqVO request = new EtlComponentVerifyReqVO();
        request.setComponentType("table-input");
        request.setTaskParams(Map.of("datasourceId", 7, "table", "customer"));

        var result = service.verify(request);

        assertTrue(result.valid(), () -> result.issues().toString());
    }

    @Test
    void rejectsUnsupportedComponentWithoutExecutingIt() {
        EtlComponentVerifyReqVO request = new EtlComponentVerifyReqVO();
        request.setComponentType("unsupported-qdata-component");
        request.setTaskParams(Map.of());

        var result = service.verify(request);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> "UNSUPPORTED_TOOL".equals(issue.code())));
    }

    @Test
    void defersQualityPortTopologyToFullGraphVerification() {
        EtlComponentVerifyReqVO request = new EtlComponentVerifyReqVO();
        request.setComponentType("quality-check");
        request.setToolType(2);
        request.setTaskParams(Map.of("ruleSetId", 3, "fieldBindings", List.of(Map.of("field", "id"))));

        var result = service.verify(request);

        assertTrue(result.valid(), () -> result.issues().toString());
        assertTrue(result.issues().stream().anyMatch(issue -> "WARN".equals(issue.severity())));
    }
}
