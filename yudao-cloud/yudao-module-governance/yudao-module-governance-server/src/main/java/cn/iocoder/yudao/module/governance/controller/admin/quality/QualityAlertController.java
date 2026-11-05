package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert.QualityAlertPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert.QualityAlertRespVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-告警记录 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-告警记录")
@RestController
@RequestMapping("/governance/quality-alert")
@Validated
public class QualityAlertController {

    @Resource
    private QualityAlertService alertService;

    /**
     * 获得告警记录。
     */
    @GetMapping("/get")
    @Operation(summary = "获得告警记录")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-alert:query')")
    public CommonResult<QualityAlertRespVO> getAlert(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(alertService.getAlert(id), QualityAlertRespVO.class));
    }

    /**
     * 获得告警记录分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得告警记录分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-alert:query')")
    public CommonResult<PageResult<QualityAlertRespVO>> getAlertPage(@Valid QualityAlertPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(alertService.getAlertPage(pageReqVO), QualityAlertRespVO.class));
    }

    /**
     * 顶部统计卡：危险/严重/一般/未处理/已处理数量
     */
    @GetMapping("/summary")
    @Operation(summary = "告警统计卡（危险/严重/一般/未处理/已处理）")
    @PreAuthorize("@ss.hasPermission('governance:quality-alert:query')")
    public CommonResult<Map<String, Long>> getAlertSummary() {
        return success(alertService.getAlertSummary());
    }

    /**
     * 处理告警：状态置为已处理
     */
    @PutMapping("/process")
    @LogRecord(type = "数据治理-数据质量-告警记录", subType = "处理告警",
            bizNo = "{{(#id) ?: 0}}", success = "将质量告警【{{(#id) ?: 0}}】标记为已处理",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "处理告警")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-alert:update')")
    public CommonResult<Boolean> processAlert(@RequestParam("id") Long id) {
        alertService.processAlert(id);
        return success(true);
    }

    /**
     * 删除告警记录。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-告警记录", subType = "删除告警记录",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量告警，编号【{{(#id) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除告警记录")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-alert:delete')")
    public CommonResult<Boolean> deleteAlert(@RequestParam("id") Long id) {
        alertService.deleteAlert(id);
        return success(true);
    }

}
