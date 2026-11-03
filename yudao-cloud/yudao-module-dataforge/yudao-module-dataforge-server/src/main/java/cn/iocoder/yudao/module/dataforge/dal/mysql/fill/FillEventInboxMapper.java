package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventInboxDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 在线填报-外部事件收件箱 Mapper（唯一键 (event_id) 为幂等最终保障）
 *
 * @author Li
 */
@Mapper
public interface FillEventInboxMapper extends BaseMapperX<FillEventInboxDO> {

    /**
     * 按事件编号查询填报事件收件箱。
     *
     * @param eventId 事件编号
     * @return 填报事件收件箱
     */
    default FillEventInboxDO selectByEventId(String eventId) {
        return selectOne(FillEventInboxDO::getEventId, eventId);
    }

}
