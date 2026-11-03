package cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 来源系统 Mapper
 *
 * @author Li
 */
@Mapper
public interface SystemSourceMapper extends BaseMapperX<SystemSourceDO> {

    /**
     * 按查询条件分页读取业务系统。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取业务系统结果
     */
    default PageResult<SystemSourceDO> selectPage(SystemSourcePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SystemSourceDO>()
                .likeIfPresent(SystemSourceDO::getName, reqVO.getName())
                .eqIfPresent(SystemSourceDO::getType, reqVO.getType())
                .eqIfPresent(SystemSourceDO::getStatus, reqVO.getStatus())
                .eqIfPresent(SystemSourceDO::getDeptId, reqVO.getDeptId())
                .orderByAsc(SystemSourceDO::getSortOrder)
                .orderByDesc(SystemSourceDO::getId));
    }

}