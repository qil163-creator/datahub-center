package cn.iocoder.yudao.module.governance.service.rule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 数据安全--脱敏规则（主表）；参数演进由版本表承载 Service 接口
 *
 * @author Li
 */
public interface SafetyRuleService {

    /**
     * 创建数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createRule(@Valid SafetyRuleSaveReqVO createReqVO);

    /**
     * 更新数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     * @param updateReqVO 更新信息
     */
    void updateRule(@Valid SafetyRuleSaveReqVO updateReqVO);

    /**
     * 删除数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     * @param id 编号
     */
    void deleteRule(Long id);

    /**
     * 批量删除数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     * @param ids 编号
     */
    void deleteRuleListByIds(List<Long> ids);

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     * @param id 编号
     * @return 数据安全--脱敏规则（主表）；参数演进由版本表承载
     */
    SafetyRuleDO getRule(Long id);

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     *
     * @param pageReqVO 分页查询
     * @return 数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     */
    PageResult<SafetyRuleDO> getRulePage(SafetyRulePageReqVO pageReqVO);

    // ==================== 规则版本 / 替换明细 ====================

    /**
     * 获得规则版本
     *
     * @param verId 版本编号
     * @return 规则版本，不存在时返回 null
     */
    SafetyRuleVerDO getRuleVer(Long verId);

    /**
     * 获得指定版本的替换明细（按匹配顺序）
     *
     * @param ruleVerId 版本编号
     * @return 替换明细列表
     */
    List<SafetyReplaceItemDO> getReplaceItemListByRuleVerId(Long ruleVerId);

    /**
     * 配置保存/删除时锁定规则并要求存在已发布、启用的当前版本。
     */
    SafetyMaskingSpec requireUsableMaskingSpec(Long ruleId);

    /**
     * 查询开始时冻结规则版本；不可用时返回 null，由调用方失败关闭，不得回退明文。
     */
    SafetyMaskingSpec resolveUsableMaskingSpec(Long ruleId);

    /**
     * 一次查询固定实际规则版本，避免逐行追读当前指针。
     */
    Map<Long, SafetyMaskingSpec> freezeUsableMaskingSpecs(Collection<Long> ruleIds);

    /**
     * 删除或停用前锁定规则并检查字段权限、静态任务引用。
     */
    void assertNotReferenced(Long ruleId);

}
