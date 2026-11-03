package cn.iocoder.yudao.module.governance.service.standardmapping;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;

/**
 * 手工落标与内部 API 共用的目标标识规则。
 */
public final class StandardMappingTargetSnapshot {

    /**
     * 限制标准映射目标快照工具类的直接实例化。
     */
    private StandardMappingTargetSnapshot() {
    }

    /** 元数据缺少资源标识时，使用字段编号生成稳定标识。 */
    public static String targetUrn(MetaColumnDO column) {
        return column.getResourceUrn() != null && !column.getResourceUrn().isBlank()
                ? column.getResourceUrn() : "urn:datahub:metadata-column:" + column.getId();
    }

}
