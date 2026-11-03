package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterChangeLogDO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MasterChangeLogMapper extends BaseMapperX<MasterChangeLogDO> {

    /**
     * 查询主数据变更日志分页。
     */
    default PageResult<MasterChangeLogDO> selectPage(MasterChangeLogPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterChangeLogDO>()
                .eqIfPresent(MasterChangeLogDO::getModelId, req.getModelId())
                .eqIfPresent(MasterChangeLogDO::getChangeType, req.getChangeType())
                .orderByDesc(MasterChangeLogDO::getId));
    }
}
