package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据治理-元数据采集任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaCollectTaskMapper extends BaseMapperX<MetaCollectTaskDO> {

    /**
     * 按指定条件读取并锁定元数据采集任务记录，供事务内一致性校验或修改使用。
     */
    default MetaCollectTaskDO lockTask(Long id) {
        return selectOne(new LambdaQueryWrapperX<MetaCollectTaskDO>()
                .eq(MetaCollectTaskDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 查询元数据采集任务分页。
     */
    default PageResult<MetaCollectTaskDO> selectPage(MetaCollectTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MetaCollectTaskDO>()
                .likeIfPresent(MetaCollectTaskDO::getName, reqVO.getName())
                .eqIfPresent(MetaCollectTaskDO::getCollectType, reqVO.getCollectType())
                .eqIfPresent(MetaCollectTaskDO::getScheduleEnabled, reqVO.getScheduleEnabled())
                .eqIfPresent(MetaCollectTaskDO::getDatasourceId, reqVO.getDatasourceId())
                .eqIfPresent(MetaCollectTaskDO::getSourceSystemId, reqVO.getSourceSystemId())
                .eqIfPresent(MetaCollectTaskDO::getEnabled, reqVO.getEnabled())
                .betweenIfPresent(MetaCollectTaskDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(MetaCollectTaskDO::getId));
    }

    /**
     * 按名称查询元数据采集任务。
     */
    default MetaCollectTaskDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<MetaCollectTaskDO>()
                .eq(MetaCollectTaskDO::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 查询到期的采集任务（已上线 + 调度开启 + 到达下次调度时间）
     */
    default List<MetaCollectTaskDO> selectDueList(LocalDateTime now) {
        return selectList(new LambdaQueryWrapperX<MetaCollectTaskDO>()
                .eq(MetaCollectTaskDO::getEnabled, MetaCollectTaskDO.SWITCH_ON)
                .eq(MetaCollectTaskDO::getScheduleEnabled, MetaCollectTaskDO.SWITCH_ON)
                .isNotNull(MetaCollectTaskDO::getNextRunTime)
                .le(MetaCollectTaskDO::getNextRunTime, now).last("LIMIT 100"));
    }

}
