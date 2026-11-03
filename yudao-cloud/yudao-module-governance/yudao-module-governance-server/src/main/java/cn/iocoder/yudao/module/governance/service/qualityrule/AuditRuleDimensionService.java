package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDimensionDO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 平台共享的稽查规则维度读取服务。 */
public interface AuditRuleDimensionService {

    /**
     * 读取维度列表。
     */
    List<AuditRuleDimensionDO> getDimensionList();

    /**
     * 读取维度。
     */
    AuditRuleDimensionDO getDimension(Long id);

    /**
     * 读取维度名称映射。
     */
    Map<Long, String> getDimensionNameMap(Collection<Long> ids);
}
