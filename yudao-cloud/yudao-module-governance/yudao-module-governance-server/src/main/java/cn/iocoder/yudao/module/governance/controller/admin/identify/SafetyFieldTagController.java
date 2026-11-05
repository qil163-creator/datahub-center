package cn.iocoder.yudao.module.governance.controller.admin.identify;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.service.identify.SafetyIdentifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 管理后台 - 敏感字段打标（含扫描触发）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 敏感字段打标")
@RestController
@RequestMapping("/governance/safety-field-tag")
@Validated
public class SafetyFieldTagController {

    @Resource
    private SafetyIdentifyService identifyService;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private SafetyLevelMapper levelMapper;

    // ==================== 扫描执行 ====================

    /**
     * 触发一次敏感数据扫描（异步）。
     */
    @PostMapping("/scan")
    @LogRecord(type = "数据治理-敏感字段打标", subType = "触发一次敏感数据扫描（异步）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "触发一次敏感数据扫描（异步）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "触发一次敏感数据扫描（异步）")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:scan')")
    public CommonResult<Long> triggerScan(@Valid @RequestBody SafetyScanReqVO reqVO) {
        return success(identifyService.triggerScan(reqVO));
    }

    /**
     * 获得扫描记录分页。
     */
    @GetMapping("/scan-run/page")
    @Operation(summary = "获得扫描记录分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:query')")
    public CommonResult<PageResult<SafetyScanRunRespVO>> getScanRunPage(
            @Valid SafetyScanRunPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(identifyService.getScanRunPage(pageReqVO), SafetyScanRunRespVO.class));
    }

    // ==================== 字段打标 ====================

    /**
     * 人工添加打标（直接确认并回写元数据）。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-敏感字段打标", subType = "人工添加打标（直接确认并回写元数据）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "人工添加打标（直接确认并回写元数据）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "人工添加打标（直接确认并回写元数据）")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:confirm')")
    public CommonResult<Long> createTag(@Valid @RequestBody SafetyFieldTagSaveReqVO createReqVO) {
        return success(identifyService.createTag(createReqVO));
    }

    /**
     * 批量确认打标（回写元数据分级与敏感标记）。
     */
    @PutMapping("/confirm")
    @LogRecord(type = "数据治理-敏感字段打标", subType = "批量确认打标（回写元数据分级与敏感标记）",
            bizNo = "{{(#ids) ?: 0}}", success = "批量确认打标（回写元数据分级与敏感标记）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量确认打标（回写元数据分级与敏感标记）")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:confirm')")
    public CommonResult<Boolean> confirmTags(@RequestBody List<Long> ids) {
        identifyService.confirmTags(ids);
        return success(true);
    }

    /**
     * 批量驳回打标。
     */
    @PutMapping("/reject")
    @LogRecord(type = "数据治理-敏感字段打标", subType = "批量驳回打标",
            bizNo = "{{(#ids) ?: 0}}", success = "批量驳回打标",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量驳回打标")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:confirm')")
    public CommonResult<Boolean> rejectTags(@RequestBody List<Long> ids) {
        identifyService.rejectTags(ids);
        return success(true);
    }

    /**
     * 删除打标记录。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-敏感字段打标", subType = "删除打标记录",
            bizNo = "{{(#id) ?: 0}}", success = "删除打标记录",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除打标记录")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:delete')")
    public CommonResult<Boolean> deleteTag(@RequestParam("id") Long id) {
        identifyService.deleteTag(id);
        return success(true);
    }

    /**
     * 获得字段打标分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得字段打标分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-identify:query')")
    public CommonResult<PageResult<SafetyFieldTagRespVO>> getTagPage(@Valid SafetyFieldTagPageReqVO pageReqVO) {
        PageResult<SafetyFieldTagDO> pageResult = identifyService.getTagPage(pageReqVO);
        PageResult<SafetyFieldTagRespVO> result = BeanUtils.toBean(pageResult, SafetyFieldTagRespVO.class);
        fillNames(result.getList());
        return success(result);
    }

    /**
     * 补充安全字段标签的名称。
     */
    private void fillNames(List<SafetyFieldTagRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        List<Long> categoryIds = convertList(list, SafetyFieldTagRespVO::getCategoryId);
        Map<Long, SafetyCategoryDO> categoryMap = CollUtil.isEmpty(categoryIds) ? Map.of()
                : convertMap(categoryMapper.selectByIds(categoryIds), SafetyCategoryDO::getId);
        Map<Long, SafetyLevelDO> levelMap = convertMap(levelMapper.selectList(), SafetyLevelDO::getId);
        list.forEach(vo -> {
            SafetyCategoryDO category = categoryMap.get(vo.getCategoryId());
            if (category != null) {
                vo.setCategoryName(category.getName());
            }
            SafetyLevelDO level = levelMap.get(vo.getLevelId());
            if (level != null) {
                vo.setLevelName(level.getSafetyName());
                vo.setLevelColor(level.getColor());
            }
        });
    }

}
