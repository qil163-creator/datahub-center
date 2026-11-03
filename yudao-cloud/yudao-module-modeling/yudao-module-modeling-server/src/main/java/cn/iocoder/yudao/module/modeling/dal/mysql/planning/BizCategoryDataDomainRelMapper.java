package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDataDomainRelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface BizCategoryDataDomainRelMapper extends BaseMapperX<BizCategoryDataDomainRelDO> {

    /**
     * 按业务分类编号查询业务分类数据域关系列表。
     *
     * @param bizCategoryId 业务分类编号
     * @return 按业务分类编号查询业务分类数据域关系列表结果集合
     */
    default List<BizCategoryDataDomainRelDO> selectListByBizCategoryId(Long bizCategoryId) {
        return selectList(new LambdaQueryWrapperX<BizCategoryDataDomainRelDO>()
                .eq(BizCategoryDataDomainRelDO::getBizCategoryId, bizCategoryId)
                .orderByAsc(BizCategoryDataDomainRelDO::getSortOrder)
                .orderByAsc(BizCategoryDataDomainRelDO::getId));
    }

    /**
     * 按业务分类编号集合查询业务分类数据域关系列表。
     *
     * @param bizCategoryIds 业务分类编号集合
     * @return 按业务分类编号集合查询业务分类数据域关系列表结果集合
     */
    default List<BizCategoryDataDomainRelDO> selectListByBizCategoryIds(Collection<Long> bizCategoryIds) {
        if (bizCategoryIds == null || bizCategoryIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<BizCategoryDataDomainRelDO>()
                .in(BizCategoryDataDomainRelDO::getBizCategoryId, bizCategoryIds)
                .orderByAsc(BizCategoryDataDomainRelDO::getBizCategoryId)
                .orderByAsc(BizCategoryDataDomainRelDO::getSortOrder)
                .orderByAsc(BizCategoryDataDomainRelDO::getId));
    }

    /**
     * 按业务分类编号删除业务分类数据域关系。
     *
     * @param bizCategoryId 业务分类编号
     */
    default void deleteByBizCategoryId(Long bizCategoryId) {
        delete(new LambdaQueryWrapperX<BizCategoryDataDomainRelDO>()
                .eq(BizCategoryDataDomainRelDO::getBizCategoryId, bizCategoryId));
    }

}
