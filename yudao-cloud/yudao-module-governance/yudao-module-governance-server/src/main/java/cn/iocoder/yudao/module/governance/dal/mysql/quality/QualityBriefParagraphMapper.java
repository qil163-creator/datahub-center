package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefParagraphDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-简报模板段落 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityBriefParagraphMapper extends BaseMapperX<QualityBriefParagraphDO> {

    /**
     * 按模板编号查询质量简报段落列表。
     */
    default List<QualityBriefParagraphDO> selectListByTemplateId(Long templateId) {
        return selectList(new LambdaQueryWrapperX<QualityBriefParagraphDO>()
                .eq(QualityBriefParagraphDO::getTemplateId, templateId)
                .orderByAsc(QualityBriefParagraphDO::getSortNo));
    }

    /**
     * 按模板编号删除质量简报段落记录。
     */
    default void deleteByTemplateId(Long templateId) {
        delete(new LambdaQueryWrapperX<QualityBriefParagraphDO>()
                .eq(QualityBriefParagraphDO::getTemplateId, templateId));
    }

}
