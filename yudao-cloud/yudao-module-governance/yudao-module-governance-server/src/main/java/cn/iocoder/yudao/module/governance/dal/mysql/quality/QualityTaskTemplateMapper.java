package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskTemplateDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-任务模板 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityTaskTemplateMapper extends BaseMapperX<QualityTaskTemplateDO> {

    /**
     * 查询质量任务模板分页。
     */
    default PageResult<QualityTaskTemplateDO> selectPage(QualityTaskTemplatePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityTaskTemplateDO>()
                .likeIfPresent(QualityTaskTemplateDO::getTemplateName, reqVO.getTemplateName())
                .eqIfPresent(QualityTaskTemplateDO::getDatasourceConnId, reqVO.getDatasourceConnId())
                .eqIfPresent(QualityTaskTemplateDO::getStatus, reqVO.getStatus())
                .orderByDesc(QualityTaskTemplateDO::getId));
    }

    /**
     * 按名称查询质量任务模板。
     */
    default QualityTaskTemplateDO selectByName(String templateName) {
        return selectOne(new LambdaQueryWrapperX<QualityTaskTemplateDO>()
                .eq(QualityTaskTemplateDO::getTemplateName, templateName));
    }

    /** 启用中的定时模板列表（调度扫描用） */
    default java.util.List<QualityTaskTemplateDO> selectTimerEnabledList() {
        return selectList(new LambdaQueryWrapperX<QualityTaskTemplateDO>()
                .eq(QualityTaskTemplateDO::getScheduleType, "TIMER")
                .eq(QualityTaskTemplateDO::getStatus, QualityTaskTemplateDO.STATUS_ENABLE)
                .isNotNull(QualityTaskTemplateDO::getCronExpression));
    }

}
