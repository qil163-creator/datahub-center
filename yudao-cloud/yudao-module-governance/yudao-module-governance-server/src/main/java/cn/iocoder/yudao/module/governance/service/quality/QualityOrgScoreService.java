package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScorePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScoreRespVO;

/**
 * 数据治理-数据质量-机构评分 Service 接口
 *
 * @author Li
 */
public interface QualityOrgScoreService {

    /**
     * 获得机构评分分页。
     */
    PageResult<QualityOrgScoreRespVO> getOrgScorePage(QualityOrgScorePageReqVO pageReqVO);

    /**
     * 计算评分：依赖所选日期范围内该规则集下检测任务的已执行结果记录，
     * 按机构聚合达标率并排名（先删后插，同规则集同周期可重复计算）
     *
     * @return 生成的评分记录数
     */
    int calculateScore(Long ruleSetId, String periodStart, String periodEnd);

}
