package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterMatchRunPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterMatchRunDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface MasterMatchRunMapper extends BaseMapperX<MasterMatchRunDO> {

    default PageResult<MasterMatchRunDO> selectPage(MasterMatchRunPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterMatchRunDO>()
                .eq(MasterMatchRunDO::getModelId, req.getModelId())
                .orderByDesc(MasterMatchRunDO::getId));
    }

    /** 进程中断遗留的执行中记录在超时后视为失败，释放“同一模型只允许一次执行”的占用 */
    default int failAbandoned(Long modelId, LocalDateTime startedBefore) {
        return update(null, Wrappers.lambdaUpdate(MasterMatchRunDO.class)
                .eq(MasterMatchRunDO::getModelId, modelId)
                .eq(MasterMatchRunDO::getStatus, MasterMatchRunDO.STATUS_RUNNING)
                .lt(MasterMatchRunDO::getStartedAt, startedBefore)
                .set(MasterMatchRunDO::getStatus, MasterMatchRunDO.STATUS_FAILED)
                .set(MasterMatchRunDO::getFinishedAt, LocalDateTime.now())
                .set(MasterMatchRunDO::getMessage, "执行超时或进程中断，结果未保存"));
    }
}
