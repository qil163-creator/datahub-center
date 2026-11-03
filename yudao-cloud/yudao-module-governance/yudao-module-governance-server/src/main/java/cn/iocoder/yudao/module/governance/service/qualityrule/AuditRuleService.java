package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;

/**
 * 稽查规则 Service 接口
 *
 * @author Li
 */
public interface AuditRuleService {

    /**
     * 创建稽查规则。
     */
    Long createAuditRule(AuditRuleSaveReqVO createReqVO);

    /**
     * 更新稽查规则。
     */
    void updateAuditRule(AuditRuleSaveReqVO updateReqVO);

    /**
     * 更新稽查规则状态。
     */
    void updateAuditRuleStatus(Long id, Integer status);

    /**
     * 删除稽查规则。
     */
    void deleteAuditRule(Long id);

    /**
     * 获得稽查规则。
     */
    AuditRuleDO getAuditRule(Long id);

    /**
     * 获得稽查规则分页。
     */
    PageResult<AuditRuleDO> getAuditRulePage(AuditRulePageReqVO pageReqVO);

    /**
     * 按策略生成稽查 SQL 预览（错误统计 + 错误明细）
     */
    AuditRulePreviewRespVO previewSql(AuditRulePreviewReqVO reqVO);

}
