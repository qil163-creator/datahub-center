package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据同步任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataAccessMapper extends BaseMapperX<DataAccessDO> {

    /**
     * 按查询条件分页读取数据同步任务。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据同步任务结果
     */
    default PageResult<DataAccessDO> selectPage(DataAccessPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataAccessDO>()
                .likeIfPresent(DataAccessDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(DataAccessDO::getEngineType, reqVO.getEngineType())
                .inIfPresent(DataAccessDO::getEngineType, reqVO.getEngineTypes())
                .eqIfPresent(DataAccessDO::getOrgId, reqVO.getOrgId())
                .eqIfPresent(DataAccessDO::getSourceDatabaseId, reqVO.getSourceDatabaseId())
                .eqIfPresent(DataAccessDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DataAccessDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(DataAccessDO::getId));
    }

}
