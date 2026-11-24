package cn.iocoder.yudao.module.modeling.framework.operatelog.config;

import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicRelationDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;

/**
 * 数据建模模块的操作日志「编号 → 名称」翻译函数，模板写法如 {@code {getLogicModelName{#id}}}。
 */
@Configuration(proxyBeanMethods = false)
public class ModelingOperateLogConfiguration {

    /** 逻辑模型（逻辑表、贴源表共用同一张表） */
    @Bean
    public EntityNameParseFunction logicModelNameParseFunction(LogicModelMapper mapper) {
        return new EntityNameParseFunction("getLogicModelName",
                loader(mapper::selectBatchIds, LogicModelDO::getId, LogicModelDO::getName));
    }

    @Bean
    public EntityNameParseFunction dataDomainNameParseFunction(DataDomainMapper mapper) {
        return new EntityNameParseFunction("getDataDomainName",
                loader(mapper::selectBatchIds, DataDomainDO::getId, DataDomainDO::getName));
    }

    @Bean
    public EntityNameParseFunction businessEntityNameParseFunction(BusinessEntityMapper mapper) {
        return new EntityNameParseFunction("getBusinessEntityName",
                loader(mapper::selectBatchIds, BusinessEntityDO::getId, BusinessEntityDO::getName));
    }

    @Bean
    public EntityNameParseFunction logicRelationNameParseFunction(LogicRelationMapper mapper) {
        return new EntityNameParseFunction("getLogicRelationName",
                loader(mapper::selectBatchIds, LogicRelationDO::getId, LogicRelationDO::getName));
    }

    /** 物化记录：显示其所属模型的名称 */
    @Bean
    public EntityNameParseFunction materializedModelNameParseFunction(ModelMaterializedMapper materializedMapper,
                                                                      LogicModelMapper modelMapper) {
        return new EntityNameParseFunction("getMaterializedModelName", ids -> {
            Map<Long, Long> modelIds = new HashMap<>();
            for (ModelMaterializedDO row : materializedMapper.selectBatchIds(ids)) {
                if (row.getModelId() != null) {
                    modelIds.put(row.getId(), row.getModelId());
                }
            }
            Map<Long, String> result = new HashMap<>();
            if (modelIds.isEmpty()) {
                return result;
            }
            Map<Long, String> modelNames = new HashMap<>();
            for (LogicModelDO model : modelMapper.selectBatchIds(modelIds.values())) {
                modelNames.put(model.getId(), model.getName());
            }
            modelIds.forEach((id, modelId) -> result.put(id, modelNames.get(modelId)));
            return result;
        });
    }

    // ========== 业务代码翻译（类型、模式、级别等），未收录的值原样输出 ==========

    @Bean
    public CodeLabelParseFunction modelingMaterializePublishModeLabelParseFunction() {
        return new CodeLabelParseFunction("getMaterializePublishModeLabel", Map.ofEntries(Map.entry("REBUILD", "重建"), Map.entry("INCREMENT", "增量")));
    }

    @Bean
    public CodeLabelParseFunction modelingLogicRelationTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getLogicRelationTypeLabel", Map.ofEntries(Map.entry("ONE_TO_ONE", "一对一"), Map.entry("ONE_TO_MANY", "一对多"), Map.entry("MANY_TO_MANY", "多对多")));
    }

}
