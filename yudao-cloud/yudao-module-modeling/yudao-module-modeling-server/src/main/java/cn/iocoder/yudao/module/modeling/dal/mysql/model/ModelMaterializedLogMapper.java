package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedLogPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMaterializedLogMapper extends BaseMapperX<ModelMaterializedLogDO> {

    /**
     * 按查询条件分页读取模型物化日志。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取模型物化日志结果
     */
    default PageResult<ModelMaterializedLogDO> selectPage(ModelMaterializedLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ModelMaterializedLogDO>()
                .eqIfPresent(ModelMaterializedLogDO::getMaterializedId, reqVO.getMaterializedId())
                .eqIfPresent(ModelMaterializedLogDO::getModelId, reqVO.getModelId())
                .eqIfPresent(ModelMaterializedLogDO::getStatus, reqVO.getStatus())
                .orderByDesc(ModelMaterializedLogDO::getId));
    }
}
