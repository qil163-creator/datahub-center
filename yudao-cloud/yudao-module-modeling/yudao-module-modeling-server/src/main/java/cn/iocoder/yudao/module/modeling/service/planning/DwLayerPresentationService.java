package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 分层卡片沿用规划对象的平台可见范围，批量补充负责人和独立授权的模型计数。 */
@Service
public class DwLayerPresentationService {

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private ModelOverviewMapper overviewMapper;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    /**
     * 组装数仓分层展示展示数据。
     *
     * @param layers 分层
     * @return 组装数仓分层展示展示数据结果集合
     */
    public List<DwLayerRespVO> present(List<DwLayerDO> layers) {
        if (layers.isEmpty()) {
            return List.of();
        }
        var result = BeanUtils.toBean(layers, DwLayerRespVO.class);
        var ownerIds = layers.stream().map(DwLayerDO::getOwnerUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AdminUserRespDTO> owners = ownerIds.isEmpty() ? Map.of() : adminUserApi.getUserMap(ownerIds);
        boolean canReadModels = securityFrameworkService.hasPermission("modeling:model:query");
        Map<String, Long> counts = canReadModels ? overviewMapper.selectLayerDistribution().stream()
                .collect(Collectors.toMap(ModelOverviewMapper.LayerCount::getKey,
                        ModelOverviewMapper.LayerCount::getCount)) : Map.of();
        for (var row : result) {
            var owner = row.getOwnerUserId() == null ? null : owners.get(row.getOwnerUserId());
            row.setOwnerName(owner == null ? null : owner.getNickname());
            boolean available = canReadModels && DwLayerDO.NODE_TYPE_LAYER.equals(row.getNodeType());
            row.setLogicModelCountAvailable(available);
            row.setLogicModelCount(available ? counts.getOrDefault(row.getId().toString(), 0L) : null);
        }
        return result;
    }
}
