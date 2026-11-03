package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 在线填报-通知与催办记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillNotifyRecordMapper extends BaseMapperX<FillNotifyRecordDO> {

    /** 催办频控：统计某工作项某类型在指定时间之后的发送次数 */
    default long selectCountSince(Long workItemId, Integer notifyType, LocalDateTime since) {
        return selectCount(new LambdaQueryWrapperX<FillNotifyRecordDO>()
                .eq(FillNotifyRecordDO::getWorkItemId, workItemId)
                .eq(FillNotifyRecordDO::getNotifyType, notifyType)
                .ge(FillNotifyRecordDO::getCreateTime, since));
    }

}
