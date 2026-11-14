package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionDetailRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelVersionDO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逻辑模型版本：每次发布生成不可变快照，只用于查看与比对。
 */
public interface LogicModelVersionService {

    /**
     * 在调用方的发布事务内记录一个新版本；调用方须已持有模型行锁。
     *
     * @param published      发布后的模型（含新修订号）
     * @param fields         发布后的字段
     * @param typeConfigJson 发布时的表类型口径 JSON，可为空
     * @param publishedBy    发布人
     * @param publishedTime  发布时间
     * @return 新版本
     */
    LogicModelVersionDO recordPublish(LogicModelDO published, List<ModelFieldItem> fields, String typeConfigJson,
            Long publishedBy, LocalDateTime publishedTime);

    /**
     * 模型的全部版本，最新在前。
     *
     * @param modelId 逻辑模型编号
     * @return 版本列表
     */
    List<LogicModelVersionRespVO> listVersions(Long modelId);

    /**
     * 版本快照详情。
     *
     * @param id 版本编号
     * @return 版本快照
     */
    LogicModelVersionDetailRespVO getVersion(Long id);

    /**
     * 比较两个版本，或某个版本与当前设计。
     *
     * @param modelId       逻辑模型编号
     * @param fromVersionId 基准版本编号
     * @param toVersionId   目标版本编号；为空时与当前设计比较
     * @return 比对结果
     */
    ModelDiffRespVO compare(Long modelId, Long fromVersionId, Long toVersionId);

    /**
     * 模型删除时逻辑删除其全部版本。
     *
     * @param modelId 逻辑模型编号
     */
    void deleteByModelId(Long modelId);

    /**
     * 模型表头快照；键名与迁移 0291 回填一致，空值不写入。
     *
     * @param model 逻辑模型
     * @return 表头快照
     */
    static Map<String, Object> headerSnapshot(LogicModelDO model) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        put(snapshot, "name", model.getName());
        put(snapshot, "code", model.getCode());
        put(snapshot, "tableNameNotation", model.getTableNameNotation());
        put(snapshot, "tableType", model.getTableType());
        put(snapshot, "createMode", model.getCreateMode());
        put(snapshot, "modelStyle", model.getModelStyle());
        put(snapshot, "layerId", model.getLayerId());
        put(snapshot, "warehouseLayer", model.getWarehouseLayer());
        put(snapshot, "dataDomainId", model.getDataDomainId());
        put(snapshot, "subjectDomainId", model.getSubjectDomainId());
        put(snapshot, "bizCategoryId", model.getBizCategoryId());
        put(snapshot, "entityId", model.getEntityId());
        put(snapshot, "objType", model.getObjType());
        put(snapshot, "businessType", model.getBusinessType());
        put(snapshot, "dataTimeliness", model.getDataTimeliness());
        put(snapshot, "statisticsStr", model.getStatisticsStr());
        put(snapshot, "grain", model.getGrain());
        put(snapshot, "description", model.getDescription());
        put(snapshot, "tableCase", model.getTableCase());
        put(snapshot, "documentType", model.getDocumentType());
        put(snapshot, "documentId", model.getDocumentId());
        put(snapshot, "contactUserId", model.getContactUserId());
        put(snapshot, "contactNumber", model.getContactNumber());
        put(snapshot, "enabled", model.getEnabled());
        put(snapshot, "datasourceId", model.getDatasourceId());
        put(snapshot, "dbType", model.getDbType());
        put(snapshot, "dbName", model.getDbName());
        put(snapshot, "schemaName", model.getSchemaName());
        put(snapshot, "sourceSystemId", model.getSourceSystemId());
        put(snapshot, "remark", model.getRemark());
        return snapshot;
    }

    private static void put(Map<String, Object> snapshot, String key, Object value) {
        if (value != null) {
            snapshot.put(key, value);
        }
    }
}
