package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDimensionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleDimensionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 维度属于平台共享规则目录，不读取数据质量业务类目。 */
@Service
@Validated
public class AuditRuleDimensionServiceImpl implements AuditRuleDimensionService {

    @Resource
    private AuditRuleDimensionMapper auditRuleDimensionMapper;

    /**
     * 读取维度列表。
     */
    @Override
    public List<AuditRuleDimensionDO> getDimensionList() {
        return auditRuleDimensionMapper.selectDimensionList();
    }

    /**
     * 读取维度。
     */
    @Override
    public AuditRuleDimensionDO getDimension(Long id) {
        return id == null ? null : auditRuleDimensionMapper.selectById(id);
    }

    /**
     * 读取维度名称映射。
     */
    @Override
    public Map<Long, String> getDimensionNameMap(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Map.of();
        }
        List<Long> selectedIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (selectedIds.isEmpty()) {
            return Map.of();
        }
        return auditRuleDimensionMapper.selectBatchIds(selectedIds).stream()
                .collect(Collectors.toMap(AuditRuleDimensionDO::getId, AuditRuleDimensionDO::getName));
    }
}
