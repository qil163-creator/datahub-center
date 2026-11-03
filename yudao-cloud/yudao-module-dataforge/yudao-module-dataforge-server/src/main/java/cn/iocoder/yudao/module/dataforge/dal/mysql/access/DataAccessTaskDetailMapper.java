package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessTaskDetailPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;

/**
 * 数据同步任务-表级同步结果 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataAccessTaskDetailMapper extends BaseMapperX<DataAccessTaskDetailDO> {

    /**
     * 按查询条件分页读取数据同步任务任务详情。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据同步任务任务详情结果
     */
    default PageResult<DataAccessTaskDetailDO> selectPage(DataAccessTaskDetailPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataAccessTaskDetailDO>()
                .eqIfPresent(DataAccessTaskDetailDO::getDataAccessId, reqVO.getDataAccessId())
                .eqIfPresent(DataAccessTaskDetailDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(DataAccessTaskDetailDO::getIfSuccess, reqVO.getIfSuccess())
                .likeIfPresent(DataAccessTaskDetailDO::getSourceTableName, reqVO.getSourceTableName())
                .likeIfPresent(DataAccessTaskDetailDO::getTargetTableName, reqVO.getTargetTableName())
                .orderByDesc(DataAccessTaskDetailDO::getId));
    }

    /**
     * 按数据同步任务编号删除数据同步任务任务详情。
     *
     * @param dataAccessId 数据同步任务编号
     */
    default void deleteByDataAccessId(Long dataAccessId) {
        delete(new LambdaQueryWrapperX<DataAccessTaskDetailDO>()
                .eq(DataAccessTaskDetailDO::getDataAccessId, dataAccessId));
    }

    /**
     * 按任务编号集合删除数据同步任务任务详情。
     *
     * @param taskIds 任务编号集合
     */
    default void deleteByTaskIds(Collection<Long> taskIds) {
        delete(new LambdaQueryWrapperX<DataAccessTaskDetailDO>()
                .in(DataAccessTaskDetailDO::getTaskId, taskIds));
    }

}
