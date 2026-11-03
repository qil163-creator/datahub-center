package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistApprovalDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 脱敏白名单 Service 接口
 *
 * @author Li
 */
public interface SafetyWhitelistService {

    /**
     * 创建脱敏白名单
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createWhitelist(@Valid SafetyWhitelistSaveReqVO createReqVO);

    /**
     * 更新脱敏白名单
     *
     * @param updateReqVO 更新信息
     */
    void updateWhitelist(@Valid SafetyWhitelistSaveReqVO updateReqVO);

    /**
     * 删除脱敏白名单
     *
     * @param id 编号
     */
    void deleteWhitelist(Long id);

    /**
     * 批量删除脱敏白名单
     *
     * @param ids 编号
     */
    void deleteWhitelistListByIds(List<Long> ids);

    /**
     * 获得脱敏白名单
     *
     * @param id 编号
     * @return 脱敏白名单
     */
    SafetyWhitelistDO getWhitelist(Long id);

    /**
     * 获得脱敏白名单分页
     *
     * @param pageReqVO 分页查询
     * @return 脱敏白名单分页
     */
    PageResult<SafetyWhitelistDO> getWhitelistPage(SafetyWhitelistPageReqVO pageReqVO);

    // ==================== 审批流 ====================

    /**
     * 提交审批：DRAFT / REJECTED → PENDING
     */
    void submitWhitelist(Long id);

    /**
     * 审批通过：PENDING → APPROVED，并自动启用
     */
    void approveWhitelist(Long id, String opinion);

    /**
     * 审批驳回：PENDING → REJECTED，并停用
     */
    void rejectWhitelist(Long id, String opinion);

    /**
     * 获得白名单审批记录列表
     */
    List<SafetyWhitelistApprovalDO> getWhitelistApprovalList(Long whitelistId);

    // ==================== 运行时消费 ====================

    /**
     * 查询用户是否命中脱敏白名单（已审批 + 启用 + 有效期内 + 主体匹配）
     *
     * @param userId 管理端用户编号
     * @param scene  豁免场景（如 QUERY），白名单配置为 ALL 时通配
     */
    SafetyMaskExemptRespDTO getMaskExempt(Long userId, String scene);

    /** 生成凭据的调用方不能将不可用的主体、角色或部门校验视为无豁免。 */
    SafetyMaskExemptRespDTO getMaskExemptStrict(Long userId, String scene);

    /**
     * 统计生效中的白名单数量（已审批 + 启用 + 有效期内）
     */
    Long countActiveWhitelist();

    /** 仅匹配调用方已核实的分类，不能把局部范围提升为全局。 */
    java.util.Set<Long> getCategoryExemptionsStrict(Long userId, String scene, java.util.Set<Long> categoryIds);

    // ==================== 子表（脱敏白名单生效主体） ====================

    /**
     * 获得脱敏白名单生效主体列表
     *
     * @param whitelistId 所属白名单编号
     * @return 脱敏白名单生效主体列表
     */
    List<SafetyWhitelistSubjectDO> getWhitelistSubjectListByWhitelistId(Long whitelistId);

}
