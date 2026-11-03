package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelinePageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 可视化 ETL 流程 Mapper
 *
 * @author Li
 */
@Mapper
public interface EtlPipelineMapper extends BaseMapperX<EtlPipelineDO> {

    /**
     * 按查询条件分页读取ETL 流程。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取ETL 流程结果
     */
    default PageResult<EtlPipelineDO> selectPage(EtlPipelinePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<EtlPipelineDO>()
                .likeIfPresent(EtlPipelineDO::getName, reqVO.getName())
                .eqIfPresent(EtlPipelineDO::getStatus, reqVO.getStatus())
                .eqIfPresent(EtlPipelineDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(EtlPipelineDO::getId));
    }

    /**
     * 按名称查询ETL 流程。
     *
     * @param name 名称
     * @return ETL 流程
     */
    default EtlPipelineDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<EtlPipelineDO>()
                .eq(EtlPipelineDO::getName, name)
                .last("LIMIT 1"));
    }

    /** 有界控制面扫描；满队列不阻塞其他任务的调度机会。 */
    default List<EtlPipelineDO> selectDueList(int limit, int pendingLimit) {
        return selectList(new LambdaQueryWrapperX<EtlPipelineDO>()
                .eq(EtlPipelineDO::getStatus, EtlPipelineDO.STATUS_RELEASED)
                .eq(EtlPipelineDO::getScheduleStatus, EtlPipelineDO.SCHEDULE_ON)
                .isNotNull(EtlPipelineDO::getPublishedConfigJson)
                .isNotNull(EtlPipelineDO::getPublishedExecutionUserId)
                .apply("next_run_time <= clock_timestamp()")
                .apply("(SELECT count(*) FROM dh_dataforge_etl_record pending WHERE 1=1"
                                + " AND pending.pipeline_id=dh_dataforge_etl_pipeline.id AND pending.protocol_version=1"
                                + " AND pending.run_status=1 AND NOT pending.occupies_slot AND pending.deleted=0) < {0}",
                        pendingLimit)
                .orderByAsc(EtlPipelineDO::getNextRunTime, EtlPipelineDO::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 10000))));
    }

}
