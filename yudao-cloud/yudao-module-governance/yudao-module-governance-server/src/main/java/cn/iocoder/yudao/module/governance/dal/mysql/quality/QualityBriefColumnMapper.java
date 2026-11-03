package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefColumnDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据治理-数据质量-简报模板附列 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityBriefColumnMapper extends BaseMapperX<QualityBriefColumnDO> {

    /**
     * 按段落编号查询质量简报字段列表。
     */
    default List<QualityBriefColumnDO> selectListByParagraphId(Long paragraphId) {
        return selectList(new LambdaQueryWrapperX<QualityBriefColumnDO>()
                .eq(QualityBriefColumnDO::getParagraphId, paragraphId)
                .orderByAsc(QualityBriefColumnDO::getSortNo));
    }

    /**
     * 按段落编号查询质量简报字段列表。
     */
    default List<QualityBriefColumnDO> selectListByParagraphIds(Collection<Long> paragraphIds) {
        if (CollUtil.isEmpty(paragraphIds)) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityBriefColumnDO>()
                .in(QualityBriefColumnDO::getParagraphId, paragraphIds)
                .orderByAsc(QualityBriefColumnDO::getSortNo));
    }

    /**
     * 按段落编号删除质量简报字段记录。
     */
    default void deleteByParagraphId(Long paragraphId) {
        delete(new LambdaQueryWrapperX<QualityBriefColumnDO>()
                .eq(QualityBriefColumnDO::getParagraphId, paragraphId));
    }

    /**
     * 按段落编号删除质量简报字段记录。
     */
    default void deleteByParagraphIds(Collection<Long> paragraphIds) {
        if (CollUtil.isEmpty(paragraphIds)) {
            return;
        }
        delete(new LambdaQueryWrapperX<QualityBriefColumnDO>()
                .in(QualityBriefColumnDO::getParagraphId, paragraphIds));
    }

}
