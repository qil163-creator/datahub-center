package cn.iocoder.yudao.module.governance.dal.mysql.standardversion;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardversion.StandardObjectVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据标准--标准对象版本快照 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardObjectVersionMapper extends BaseMapperX<StandardObjectVersionDO> {

    /**
     * 按对象查询标准对象版本列表。
     */
    default List<StandardObjectVersionDO> selectListByObject(String objectType, Long objectId) {
        return selectList(new LambdaQueryWrapperX<StandardObjectVersionDO>()
                .eq(StandardObjectVersionDO::getObjectType, objectType)
                .eq(StandardObjectVersionDO::getObjectId, objectId)
                .orderByDesc(StandardObjectVersionDO::getVersion));
    }

    /**
     * 按对象查询上限版本。
     */
    default Integer selectMaxVersionByObject(String objectType, Long objectId) {
        StandardObjectVersionDO latest = selectOne(new LambdaQueryWrapperX<StandardObjectVersionDO>()
                .eq(StandardObjectVersionDO::getObjectType, objectType)
                .eq(StandardObjectVersionDO::getObjectId, objectId)
                .orderByDesc(StandardObjectVersionDO::getVersion)
                .last("LIMIT 1"));
        return latest != null ? latest.getVersion() : 0;
    }

}
