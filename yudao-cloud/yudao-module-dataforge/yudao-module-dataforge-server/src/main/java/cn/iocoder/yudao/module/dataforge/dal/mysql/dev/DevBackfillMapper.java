package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DevBackfillMapper extends BaseMapperX<DevBackfillDO> {

    /**
     * 锁定批次行；推进、取消在同一行锁下串行。
     *
     * @param id 批次编号
     * @return 批次
     */
    @Select("SELECT * FROM dh_dataforge_dev_backfill WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    DevBackfillDO selectForUpdate(@Param("id") Long id);

    /**
     * 查询待推进的批次编号。
     *
     * @param limit 数量上限
     * @return 批次编号
     */
    @Select("SELECT id FROM dh_dataforge_dev_backfill WHERE status = 'RUNNING' AND deleted = 0 "
            + "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<Long> selectRunningIds(@Param("limit") int limit);

    default DevBackfillDO selectByRequestId(String requestId) {
        return selectOne(DevBackfillDO::getRequestId, requestId);
    }

    default boolean existsRunning(Long taskId) {
        return selectCount(new LambdaQueryWrapperX<DevBackfillDO>().eq(DevBackfillDO::getTaskId, taskId)
                .eq(DevBackfillDO::getStatus, DevBackfillDO.STATUS_RUNNING)) > 0;
    }

    default PageResult<DevBackfillDO> selectPage(DevBackfillPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DevBackfillDO>()
                .eqIfPresent(DevBackfillDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(DevBackfillDO::getStatus, reqVO.getStatus())
                .orderByDesc(DevBackfillDO::getId));
    }
}
