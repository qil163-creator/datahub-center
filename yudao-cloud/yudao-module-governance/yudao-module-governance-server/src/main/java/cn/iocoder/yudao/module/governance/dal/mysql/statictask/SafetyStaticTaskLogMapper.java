package cn.iocoder.yudao.module.governance.dal.mysql.statictask;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 静态脱敏任务执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyStaticTaskLogMapper extends BaseMapperX<SafetyStaticTaskLogDO> {

    /**
     * 查询静态脱敏任务日志分页。
     */
    default PageResult<SafetyStaticTaskLogDO> selectPage(SafetyStaticTaskLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyStaticTaskLogDO>()
                .eqIfPresent(SafetyStaticTaskLogDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(SafetyStaticTaskLogDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(SafetyStaticTaskLogDO::getId));
    }

}
