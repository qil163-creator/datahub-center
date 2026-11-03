package cn.iocoder.yudao.module.governance.service.identify;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyScanRunDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 敏感数据识别与字段打标 Service 接口
 *
 * @author Li
 */
public interface SafetyIdentifyService {

    // ==================== 识别规则 ====================

    /**
     * 创建敏感识别的规则。
     */
    Long createRule(@Valid SafetyIdentifyRuleSaveReqVO createReqVO);

    /**
     * 更新敏感识别的规则。
     */
    void updateRule(@Valid SafetyIdentifyRuleSaveReqVO updateReqVO);

    /**
     * 删除敏感识别的规则。
     */
    void deleteRule(Long id);

    /**
     * 读取敏感识别的规则。
     */
    SafetyIdentifyRuleDO getRule(Long id);

    /**
     * 读取规则分页。
     */
    PageResult<SafetyIdentifyRuleDO> getRulePage(SafetyIdentifyRulePageReqVO pageReqVO);

    // ==================== 扫描执行 ====================

    /**
     * 触发一次敏感数据扫描（异步执行）
     *
     * @return 扫描记录编号
     */
    Long triggerScan(@Valid SafetyScanReqVO reqVO);

    /**
     * 读取扫描运行分页。
     */
    PageResult<SafetyScanRunDO> getScanRunPage(SafetyScanRunPageReqVO pageReqVO);

    // ==================== 字段打标 ====================

    /**
     * 人工添加打标（直接确认并回写元数据）
     */
    Long createTag(@Valid SafetyFieldTagSaveReqVO createReqVO);

    /**
     * 批量确认打标：置为 CONFIRMED 并回写元数据字段的分级与敏感标记
     */
    void confirmTags(List<Long> ids);

    /**
     * 批量驳回打标
     */
    void rejectTags(List<Long> ids);

    /**
     * 删除标签。
     */
    void deleteTag(Long id);

    /**
     * 读取标签分页。
     */
    PageResult<SafetyFieldTagDO> getTagPage(SafetyFieldTagPageReqVO pageReqVO);

}
