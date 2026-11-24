package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-数据更正 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报数据更正")
@RestController
@RequestMapping("/dataforge/fill-correction")
@Validated
public class FillCorrectionController {

    @Resource
    private FillCorrectionService correctionService;

    /**
     * 发起数据更正。
     *
     * @param createReqVO 创建参数
     * @return 填报更正
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-在线填报数据更正", subType = "发起数据更正", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "针对填报提交【{getFillSubmissionName{#createReqVO.submissionId}}】发起了数据更正", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发起数据更正", description = "仅最新已合并快照可更正；实例转入整改中，重新提交后走新 BPM 审批并幂等覆盖")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-correction:create')")
    public CommonResult<Long> createCorrection(@Valid @RequestBody FillCorrectionCreateReqVO createReqVO) {
        return success(correctionService.createCorrection(createReqVO));
    }

    /**
     * 取消更正（仅草拟中且尚未重新提交）。
     *
     * @param id 记录编号
     * @return 填报更正
     */
    @PutMapping("/cancel")
    @LogRecord(type = "数据开发-在线填报数据更正", subType = "取消更正（仅草拟中且尚未重新提交）", bizNo = "{{(#id) ?: 0}}",
            success = "取消了【{getFillCorrectionName{#id}}】尚未重新提交的数据更正", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "取消更正（仅草拟中且尚未重新提交）")
    @Parameter(name = "id", description = "更正实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-correction:create')")
    public CommonResult<Boolean> cancelCorrection(@RequestParam("id") Long id) {
        correctionService.cancelCorrection(id);
        return success(true);
    }

    /**
     * 获得数据更正分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据更正分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据更正分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-merge:query')")
    public CommonResult<PageResult<FillCorrectionRespVO>> getCorrectionPage(
            @Valid FillCorrectionPageReqVO pageReqVO) {
        return success(correctionService.getCorrectionPage(pageReqVO));
    }

}
