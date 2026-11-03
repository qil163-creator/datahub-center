package cn.iocoder.yudao.module.supply.service.apilog;

import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogStatisticsRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiLogStatisticsIsolationTest {

    @Test
    void groupsByApiIdNotRenamedLabel() {
        ApiLogMapper apiLogMapper = mock(ApiLogMapper.class);
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        when(dataApiMapper.selectCount()).thenReturn(2L);
        when(dataApiMapper.selectCount(DataApiDO::getStatus, DataApiDO.STATUS_ONLINE)).thenReturn(2L);
        when(apiLogMapper.selectList(any())).thenReturn(List.of(
                log(1L, "旧名称", 10),
                log(1L, "新名称", 20),
                log(2L, "新名称", 30)
        ));
        ApiLogServiceImpl service = new ApiLogServiceImpl();
        ReflectionTestUtils.setField(service, "apiLogMapper", apiLogMapper);
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "apiCategoryMapper", mock(cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper.class));

        ApiLogStatisticsRespVO result = service.getStatistics(7);

        assertThat(result.getTopApis()).hasSize(2);
        assertThat(result.getTopApis()).anySatisfy(item -> {
            assertThat(item.getApiId()).isEqualTo(1L);
            assertThat(item.getValue()).isEqualTo(2L);
        });
        assertThat(result.getTopApis()).anySatisfy(item -> {
            assertThat(item.getApiId()).isEqualTo(2L);
            assertThat(item.getValue()).isEqualTo(1L);
        });
    }

    private static ApiLogDO log(Long apiId, String name, int minute) {
        return ApiLogDO.builder()
                .apiId(apiId)
                .apiName(name)
                .status(ApiLogDO.STATUS_SUCCESS)
                .durationMs(10L)
                .callTime(LocalDateTime.now().withMinute(minute))
                .build();
    }
}
