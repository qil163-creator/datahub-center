package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityBriefTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-简报模板 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-简报模板")
@RestController
@RequestMapping("/governance/quality-brief-template")
@Validated
public class QualityBriefTemplateController {

    @Resource
    private QualityBriefTemplateService templateService;

    /**
     * 创建简报模板（含段落 + 附列配置）。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-数据质量-简报模板", subType = "创建简报模板（含段落 + 附列配置）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建简报模板（含段落 + 附列配置）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建简报模板（含段落 + 附列配置）")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:create')")
    public CommonResult<Long> createTemplate(@Valid @RequestBody QualityBriefTemplateSaveReqVO createReqVO) {
        return success(templateService.createTemplate(createReqVO));
    }

    /**
     * 更新简报模板（段落全量覆盖）。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-简报模板", subType = "更新简报模板（段落全量覆盖）",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新简报模板（段落全量覆盖）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新简报模板（段落全量覆盖）")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:update')")
    public CommonResult<Boolean> updateTemplate(@Valid @RequestBody QualityBriefTemplateSaveReqVO updateReqVO) {
        templateService.updateTemplate(updateReqVO);
        return success(true);
    }

    /**
     * 删除简报模板。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-简报模板", subType = "删除简报模板",
            bizNo = "{{(#id) ?: 0}}", success = "删除简报模板",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除简报模板")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:delete')")
    public CommonResult<Boolean> deleteTemplate(@RequestParam("id") Long id) {
        templateService.deleteTemplate(id);
        return success(true);
    }

    /**
     * 模板详情（含段落 + 附列配置）
     */
    @GetMapping("/get")
    @Operation(summary = "获得简报模板详情（含段落 + 附列）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<QualityBriefTemplateRespVO> getTemplate(@RequestParam("id") Long id) {
        return success(templateService.getTemplate(id));
    }

    /**
     * 获得简报模板分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得简报模板分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<PageResult<QualityBriefTemplateRespVO>> getTemplatePage(
            @Valid QualityBriefTemplatePageReqVO pageReqVO) {
        return success(templateService.getTemplatePage(pageReqVO));
    }

    /**
     * 获得简报模板精简列表（启用状态）。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得简报模板精简列表（启用状态）")
    @PreAuthorize("@ss.hasPermission('governance:quality-brief:query')")
    public CommonResult<List<QualityBriefTemplateRespVO>> getTemplateSimpleList() {
        return success(templateService.getTemplateSimpleList());
    }

}
