package cn.iocoder.yudao.module.governance.dal.mysql.profile;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据探查-字段指标 Mapper。
 *
 * @author Li
 */
@Mapper
public interface ProfileColumnMapper extends BaseMapperX<ProfileColumnDO> {

    /**
     * 按探查记录查询字段指标，按字段序号排序。
     */
    default List<ProfileColumnDO> selectListByRunId(Long runId) {
        return selectList(new LambdaQueryWrapperX<ProfileColumnDO>()
                .eq(ProfileColumnDO::getRunId, runId)
                .orderByAsc(ProfileColumnDO::getOrdinalPosition)
                .orderByAsc(ProfileColumnDO::getId));
    }

}
