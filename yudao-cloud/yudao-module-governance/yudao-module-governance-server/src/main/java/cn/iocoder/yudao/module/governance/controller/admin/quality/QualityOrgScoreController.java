package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScorePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScoreRespVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityOrgScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-机构评分 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-质量评分")
@RestController
@RequestMapping("/governance/quality-org-score")
@Validated
public class QualityOrgScoreController {

    @Resource
    private QualityOrgScoreService orgScoreService;

    /**
     * 获得机构评分分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得机构评分分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-org-score:query')")
    public CommonResult<PageResult<QualityOrgScoreRespVO>> getOrgScorePage(
            @Valid QualityOrgScorePageReqVO pageReqVO) {
        return success(orgScoreService.getOrgScorePage(pageReqVO));
    }

    /**
     * 计算评分：依赖所选日期范围内该规则集下检测任务的已执行结果记录，
     *  按机构聚合达标率并排名（先删后插，同规则集同周期可重复计算）
     *
     *  @return 生成的评分记录数
     */
    @PostMapping("/calculate")
    @LogRecord(type = "数据治理-数据质量-质量评分", subType = "计算评分（依赖周期内该规则集下已执行的检测结果）",
            bizNo = "{{(#ruleSetId) ?: 0}}", success = "计算评分（依赖周期内该规则集下已执行的检测结果）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "计算评分（依赖周期内该规则集下已执行的检测结果）")
    @PreAuthorize("@ss.hasPermission('governance:quality-org-score:create')")
    public CommonResult<Integer> calculateScore(@RequestParam("ruleSetId") Long ruleSetId,
            @RequestParam("periodStart") String periodStart,
            @RequestParam("periodEnd") String periodEnd) {
        return success(orgScoreService.calculateScore(ruleSetId, periodStart, periodEnd));
    }

}
