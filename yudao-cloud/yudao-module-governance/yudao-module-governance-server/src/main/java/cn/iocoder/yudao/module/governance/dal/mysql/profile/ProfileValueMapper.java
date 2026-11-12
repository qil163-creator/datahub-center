package cn.iocoder.yudao.module.governance.dal.mysql.profile;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据探查-Top 值 Mapper。
 *
 * @author Li
 */
@Mapper
public interface ProfileValueMapper extends BaseMapperX<ProfileValueDO> {

    /**
     * 按探查记录查询 Top 值，按字段与排名排序。
     */
    default List<ProfileValueDO> selectListByRunId(Long runId) {
        return selectList(new LambdaQueryWrapperX<ProfileValueDO>()
                .eq(ProfileValueDO::getRunId, runId)
                .orderByAsc(ProfileValueDO::getProfileColumnId)
                .orderByAsc(ProfileValueDO::getRankNo));
    }

}
