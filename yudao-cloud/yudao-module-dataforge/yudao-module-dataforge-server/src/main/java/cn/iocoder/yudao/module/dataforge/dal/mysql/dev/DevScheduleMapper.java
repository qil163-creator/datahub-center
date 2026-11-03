package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DevScheduleMapper extends BaseMapperX<DevScheduleDO> {

    /**
     * 查询到期维护。
     *
     * @param now 当前时间
     * @return 到期维护结果集合
     */
    default List<DevScheduleDO> selectDueMaintenance(LocalDateTime now) {
        return selectList(Wrappers.<DevScheduleDO>lambdaQuery()
                .and(q -> q.isNull(DevScheduleDO::getLeaseUntil).or().lt(DevScheduleDO::getLeaseUntil, now))
                .and(q -> q.lt(DevScheduleDO::getAttempts, 5).ne(DevScheduleDO::getSyncStatus, "SYNCED")
                        .or(a -> a.eq(DevScheduleDO::getDesiredEnabled, true).eq(DevScheduleDO::getSyncedEnabled, true)
                                .eq(DevScheduleDO::getSyncStatus, "SYNCED")))
                .orderByAsc(DevScheduleDO::getUpdateTime).last("LIMIT 100"));
    }
}
