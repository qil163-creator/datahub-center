package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;

/**
 * 清洗规则 Service 接口
 *
 * @author Li
 */
public interface CleanRuleService {

    /**
     * 创建清洗规则。
     */
    Long createCleanRule(CleanRuleSaveReqVO createReqVO);

    /**
     * 更新清洗规则。
     */
    void updateCleanRule(CleanRuleSaveReqVO updateReqVO);

    /**
     * 更新清洗规则状态。
     */
    void updateCleanRuleStatus(Long id, Integer status);

    /**
     * 删除清洗规则。
     */
    void deleteCleanRule(Long id);

    /**
     * 获得清洗规则。
     */
    CleanRuleDO getCleanRule(Long id);

    /**
     * 获得清洗规则分页。
     */
    PageResult<CleanRuleDO> getCleanRulePage(CleanRulePageReqVO pageReqVO);

    /**
     * 在线测试清洗规则：对样例数据执行清洗函数并返回结果
     */
    CleanRuleTestRespVO testCleanRule(CleanRuleTestReqVO reqVO);

}
