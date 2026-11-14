package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface LogicModelVersionMapper extends BaseMapperX<LogicModelVersionDO> {

    /**
     * 按版本号倒序读取模型的全部版本。
     *
     * @param modelId 逻辑模型编号
     * @return 版本列表，最新版本在前
     */
    default List<LogicModelVersionDO> selectListByModelId(Long modelId) {
        return selectList(new LambdaQueryWrapperX<LogicModelVersionDO>()
                .eq(LogicModelVersionDO::getModelId, modelId)
                .orderByDesc(LogicModelVersionDO::getVersionNo));
    }

    /**
     * 读取模型的最新版本。
     *
     * @param modelId 逻辑模型编号
     * @return 最新版本，没有版本时为空
     */
    default LogicModelVersionDO selectLatest(Long modelId) {
        return selectOne(new LambdaQueryWrapperX<LogicModelVersionDO>()
                .eq(LogicModelVersionDO::getModelId, modelId)
                .orderByDesc(LogicModelVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 逻辑删除模型的全部版本。
     *
     * @param modelId 逻辑模型编号
     */
    default void deleteByModelId(Long modelId) {
        delete(LogicModelVersionDO::getModelId, modelId);
    }
}
