package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.controller.admin.master.MasterDistributeController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MasterDistributeReviewContractTest {
    @Test void unknownMustHaveAnIndependentlyAuthorizedResolutionEntry() {
        var endpoint = Arrays.stream(MasterDistributeController.class.getMethods())
                .filter(m -> m.isAnnotationPresent(PutMapping.class)
                        && Arrays.asList(m.getAnnotation(PutMapping.class).value()).contains("/resolve"))
                .findFirst();
        assertTrue(endpoint.isPresent(), "UNKNOWN 只有阻塞，没有受控人工核查入口");
        assertEquals("@ss.hasPermission('governance:master-distribute:resolve')",
                endpoint.orElseThrow().getAnnotation(PreAuthorize.class).value());
    }
}
