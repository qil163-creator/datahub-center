package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevQualityResolveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DevQualityResolutionTest {
    @Test void unknownQualityCanOnlyBeClosedAsFailureWithActorAndLeaseRevocation() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "quality-resolution"), DevRecordDO.class);
        DevRecordServiceImpl service = new DevRecordServiceImpl();
        DevRecordMapper mapper = mock(DevRecordMapper.class);
        ReflectionTestUtils.setField(service, "recordMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(DevRecordDO.builder().id(1L)
                .status("SUCCESS").qualityStatus("UNKNOWN").leaseOwner("expired-worker").build());
        List<Map<String, Object>> writes = new ArrayList<>();
        when(mapper.update(isNull(), any())).thenAnswer(inv -> {
            LambdaUpdateWrapper<?> wrapper = inv.getArgument(1);
            String sqlSet = String.valueOf(wrapper.getSqlSet()).toLowerCase().replace("_", "");
            String segment = String.valueOf(wrapper.getSqlSegment()).toLowerCase().replace("_", "");
            assertTrue(sqlSet.contains("leaseowner"));
            assertTrue(segment.contains("qualitystatus"));
            writes.add(new HashMap<>(wrapper.getParamNameValuePairs())); return 1;
        });
        DevQualityResolveReqVO req = new DevQualityResolveReqVO();
        req.setId(1L); req.setExpectedStatus("UNKNOWN"); req.setReason("verified quality worker terminated");
        assertThrows(ServiceException.class, () -> service.resolveQuality(req));
        req.setConfirmedTerminated(true);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(77L);
            service.resolveQuality(req);
        }
        assertTrue(writes.get(0).containsValue("FAILED"));
        assertTrue(writes.get(0).containsValue(77L));
        assertFalse(writes.get(0).containsValue("PASSED"));
    }
}
