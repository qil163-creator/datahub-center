package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DevBackfillItemMapper extends BaseMapperX<DevBackfillItemDO> {

    default List<DevBackfillItemDO> selectListByBackfillId(Long backfillId) {
        return selectList(new LambdaQueryWrapperX<DevBackfillItemDO>()
                .eq(DevBackfillItemDO::getBackfillId, backfillId).orderByAsc(DevBackfillItemDO::getSeq));
    }

    default List<DevBackfillItemDO> selectListByStatus(Long backfillId, String status) {
        return selectList(new LambdaQueryWrapperX<DevBackfillItemDO>()
                .eq(DevBackfillItemDO::getBackfillId, backfillId).eq(DevBackfillItemDO::getStatus, status)
                .orderByAsc(DevBackfillItemDO::getSeq));
    }

    default DevBackfillItemDO selectNextPending(Long backfillId) {
        return selectOne(new LambdaQueryWrapperX<DevBackfillItemDO>()
                .eq(DevBackfillItemDO::getBackfillId, backfillId)
                .eq(DevBackfillItemDO::getStatus, DevBackfillItemDO.STATUS_PENDING)
                .orderByAsc(DevBackfillItemDO::getSeq).last("LIMIT 1"));
    }
}
