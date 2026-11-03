package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTagRelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-元数据-标签关联 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaTagRelMapper extends BaseMapperX<MetaTagRelDO> {

    /**
     * 按对象查询元数据标签关联列表。
     */
    default List<MetaTagRelDO> selectListByObject(Integer objectType, Long objectId) {
        return selectList(new LambdaQueryWrapperX<MetaTagRelDO>()
                .eq(MetaTagRelDO::getObjectType, objectType)
                .eq(MetaTagRelDO::getObjectId, objectId));
    }

    /**
     * 按标签编号查询元数据标签关联数量。
     */
    default Long selectCountByTagId(Long tagId) {
        return selectCount(MetaTagRelDO::getTagId, tagId);
    }

    /**
     * 按唯一性查询元数据标签关联。
     */
    default MetaTagRelDO selectByUnique(Long tagId, Integer objectType, Long objectId) {
        return selectOne(new LambdaQueryWrapperX<MetaTagRelDO>()
                .eq(MetaTagRelDO::getTagId, tagId)
                .eq(MetaTagRelDO::getObjectType, objectType)
                .eq(MetaTagRelDO::getObjectId, objectId)
                .last("LIMIT 1"));
    }

}
