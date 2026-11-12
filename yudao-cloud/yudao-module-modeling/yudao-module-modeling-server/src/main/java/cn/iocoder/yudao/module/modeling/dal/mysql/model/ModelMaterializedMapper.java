package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface ModelMaterializedMapper extends BaseMapperX<ModelMaterializedDO> {


    /**
     * 按模型编号集合查询模型物化列表。
     *
     * @param modelIds 模型编号集合
     * @return 按模型编号集合查询模型物化列表结果集合
     */
    default List<ModelMaterializedDO> selectListByModelIds(Collection<Long> modelIds) {
        if (CollUtil.isEmpty(modelIds)) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<ModelMaterializedDO>()
                .in(ModelMaterializedDO::getModelId, modelIds)
                .orderByDesc(ModelMaterializedDO::getUpdateTime)
                .orderByDesc(ModelMaterializedDO::getId));
    }

    /**
     * 查询全部对应最新状态。
     *
     * @return 全部对应最新状态结果集合
     */
    default List<ModelMaterializedDO> selectAllForLatestStatus() {
        return selectList(new LambdaQueryWrapperX<ModelMaterializedDO>()
                .orderByDesc(ModelMaterializedDO::getUpdateTime)
                .orderByDesc(ModelMaterializedDO::getId));
    }

    /**
     * 查询模型最新一条成功物化记录，供指标查询定位物理表与冻结字段映射。
     *
     * @param modelId 模型编号
     * @return 最新成功物化，没有时为空
     */
    default ModelMaterializedDO selectLatestSuccess(Long modelId) {
        return selectOne(new LambdaQueryWrapperX<ModelMaterializedDO>()
                .eq(ModelMaterializedDO::getModelId, modelId)
                .eq(ModelMaterializedDO::getStatus, ModelMaterializedDO.STATUS_SUCCESS)
                .orderByDesc(ModelMaterializedDO::getUpdateTime)
                .orderByDesc(ModelMaterializedDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按模型与数据源查询模型物化。
     *
     * @param modelId 模型编号
     * @param datasourceId 数据源编号
     * @return 模型物化
     */
    default ModelMaterializedDO selectByModelAndDatasource(Long modelId, Long datasourceId) {
        return selectOne(new LambdaQueryWrapperX<ModelMaterializedDO>()
                .eq(ModelMaterializedDO::getModelId, modelId)
                .eq(ModelMaterializedDO::getDatasourceId, datasourceId)
                .last("LIMIT 1"));
    }
}
