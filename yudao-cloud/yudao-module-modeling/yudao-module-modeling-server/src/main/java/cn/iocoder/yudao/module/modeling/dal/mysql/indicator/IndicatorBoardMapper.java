package cn.iocoder.yudao.module.modeling.dal.mysql.indicator;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorBoardDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 指标看板 Mapper
 */
@Mapper
public interface IndicatorBoardMapper extends BaseMapperX<IndicatorBoardDO> {

    /**
     * 列表，按名称模糊过滤，最多 200 个。
     */
    default List<IndicatorBoardDO> selectListByName(String name) {
        return selectList(new LambdaQueryWrapperX<IndicatorBoardDO>()
                .likeIfPresent(IndicatorBoardDO::getName, name)
                .orderByDesc(IndicatorBoardDO::getUpdateTime)
                .last("LIMIT 200"));
    }

    /**
     * 本人看板按修订号 CAS 更新。
     */
    default int updateByRevision(IndicatorBoardDO board, Integer expectedRevision) {
        return update(null, new LambdaUpdateWrapper<IndicatorBoardDO>()
                .set(IndicatorBoardDO::getName, board.getName())
                .set(IndicatorBoardDO::getDescription, board.getDescription())
                .set(IndicatorBoardDO::getWidgetsJson, board.getWidgetsJson())
                .setSql("revision = revision + 1")
                .eq(IndicatorBoardDO::getId, board.getId())
                .eq(IndicatorBoardDO::getOwnerUserId, board.getOwnerUserId())
                .eq(IndicatorBoardDO::getRevision, expectedRevision));
    }
}
