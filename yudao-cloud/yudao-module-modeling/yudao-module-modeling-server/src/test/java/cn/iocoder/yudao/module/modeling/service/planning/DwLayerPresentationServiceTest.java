package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DwLayerPresentationServiceTest {
    @Mock private AdminUserApi adminUserApi;
    @Mock private ModelOverviewMapper overviewMapper;
    @Mock private SecurityFrameworkService securityFrameworkService;
    @InjectMocks private DwLayerPresentationService service;

    @Test
    void countsRequireModelPermissionAndOwnerlessLayersAreSupported() {
        var rows = service.present(List.of(layer(1L, "LAYER", null)));
        assertNull(rows.getFirst().getOwnerName());
        assertNull(rows.getFirst().getLogicModelCount());
        assertFalse(rows.getFirst().isLogicModelCountAvailable());
        verifyNoInteractions(adminUserApi, overviewMapper);
    }

    @Test
    void enrichesAllLayersWithOneQueryAndOneOwnerBatch() {
        when(securityFrameworkService.hasPermission("modeling:model:query")).thenReturn(true);
        var owner = new AdminUserRespDTO();
        owner.setId(9L);
        owner.setNickname("负责人");
        when(adminUserApi.getUserMap(List.of(9L))).thenReturn(Map.of(9L, owner));
        var count = new ModelOverviewMapper.LayerCount();
        count.setKey("1");
        count.setCount(3L);
        when(overviewMapper.selectLayerDistribution()).thenReturn(List.of(count));
        var rows = service.present(List.of(layer(1L,"LAYER",9L), layer(2L,"LAYER",9L), layer(3L,"CATEGORY",null)));
        assertEquals(3L, rows.get(0).getLogicModelCount());
        assertEquals(0L, rows.get(1).getLogicModelCount());
        assertEquals("负责人", rows.get(0).getOwnerName());
        assertFalse(rows.get(2).isLogicModelCountAvailable());
        assertNull(rows.get(2).getLogicModelCount());
        verify(adminUserApi, times(1)).getUserMap(List.of(9L));
        verify(overviewMapper, times(1)).selectLayerDistribution();
    }

    private DwLayerDO layer(long id, String kind, Long owner) {
        var layer = new DwLayerDO();
        layer.setId(id);
        layer.setNodeType(kind);
        layer.setOwnerUserId(owner);
        return layer;
    }
}
