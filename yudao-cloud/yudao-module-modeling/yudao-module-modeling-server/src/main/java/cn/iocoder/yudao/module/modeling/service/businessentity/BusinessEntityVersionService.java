package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionCompareRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionDetailRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityRelationDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityVersionDO;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 业务实体版本：每次上线生成不可变快照，只用于查看与比对。
 */
public interface BusinessEntityVersionService {

    /**
     * 在调用方的上线事务内记录一个新版本；调用方须已持有实体行锁。
     *
     * @param row           上线的实体
     * @param revision      上线后实体的修订号
     * @param own           实体自身发出的关系
     * @param boardEntities 同板块实体，用于记录关系对端的名称与编码
     * @param publishedBy   上线操作人
     * @param publishedTime 上线时间
     * @return 新版本
     */
    BusinessEntityVersionDO recordOnline(BusinessEntityDO row, long revision, List<BusinessEntityRelationDO> own,
            Collection<BusinessEntityDO> boardEntities, Long publishedBy, LocalDateTime publishedTime);

    /**
     * 实体的全部版本，最新在前。
     *
     * @param entityId 业务实体编号
     * @return 版本列表
     */
    List<BusinessEntityVersionRespVO> listVersions(Long entityId);

    /**
     * 版本快照详情。
     *
     * @param id 版本编号
     * @return 版本快照
     */
    BusinessEntityVersionDetailRespVO getVersion(Long id);

    /**
     * 比较两个版本，或某个版本与当前设计。
     *
     * @param entityId      业务实体编号
     * @param fromVersionId 基准版本编号
     * @param toVersionId   目标版本编号；为空时与当前设计比较
     * @return 比对结果
     */
    BusinessEntityVersionCompareRespVO compare(Long entityId, Long fromVersionId, Long toVersionId);

    /**
     * 实体删除时逻辑删除其全部版本。
     *
     * @param entityId 业务实体编号
     */
    void deleteByEntityId(Long entityId);

    /**
     * 实体快照；键名与迁移 0291 回填一致，空值不写入。
     *
     * @param row  实体
     * @param own  实体自身发出的关系
     * @param byId 关系对端实体，按编号索引
     * @return 快照
     */
    static Map<String, Object> snapshot(BusinessEntityDO row, List<BusinessEntityRelationDO> own,
            Map<Long, BusinessEntityDO> byId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        put(snapshot, "name", row.getName());
        put(snapshot, "code", row.getCode());
        put(snapshot, "entityType", row.getEntityType());
        put(snapshot, "entitySubtype", row.getEntitySubtype());
        put(snapshot, "dataDomainId", row.getDataDomainId());
        put(snapshot, "subjectDomainId", row.getSubjectDomainId());
        put(snapshot, "leaderUserId", row.getLeaderUserId());
        put(snapshot, "leaderPhone", row.getLeaderPhone());
        put(snapshot, "description", row.getDescription());
        List<Map<String, Object>> relations = new ArrayList<>();
        own.stream()
                .filter(relation -> byId.containsKey(relation.getTargetEntityId()))
                .sorted(Comparator.comparing(BusinessEntityRelationDO::getRelationType)
                        .thenComparing(BusinessEntityRelationDO::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BusinessEntityRelationDO::getTargetEntityId))
                .forEach(relation -> {
                    BusinessEntityDO target = byId.get(relation.getTargetEntityId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    put(item, "relationType", relation.getRelationType());
                    put(item, "entityId", target.getId());
                    put(item, "name", target.getName());
                    put(item, "code", target.getCode());
                    relations.add(item);
                });
        snapshot.put("relations", relations);
        return snapshot;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
