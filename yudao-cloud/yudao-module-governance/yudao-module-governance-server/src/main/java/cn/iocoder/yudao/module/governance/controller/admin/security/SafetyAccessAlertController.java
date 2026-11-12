package cn.iocoder.yudao.module.governance.controller.admin.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertHandleReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertRespVO;
import cn.iocoder.yudao.module.governance.service.security.SafetyAccessAlertService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 异常访问告警。
 * <p>
 * 数据范围：平台级安全审计，按权限码控制，平台内全可见；告警只含统计值，不含访问到的数据。
 */
@Tag(name = "管理后台 - 异常访问告警")
@RestController
@RequestMapping("/governance/access-alert")
@Validated
public class SafetyAccessAlertController {

    @Resource
    private SafetyAccessAlertService alertService;

    /**
     * 告警分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得异常访问告警分页")
    @PreAuthorize("@ss.hasPermission('governance:access-alert:query')")
    public CommonResult<PageResult<SafetyAccessAlertRespVO>> page(@Valid SafetyAccessAlertPageReqVO reqVO) {
        return success(alertService.page(reqVO));
    }

    /**
     * 处理告警。
     */
    @PutMapping("/handle")
    @Operation(summary = "处理异常访问告警")
    @PreAuthorize("@ss.hasPermission('governance:access-alert:handle')")
    @LogRecord(type = "数据治理-异常访问", subType = "处理告警", bizNo = "{{#reqVO.id}}",
            success = "将异常访问告警【{{#reqVO.id}}】处理为【{{#reqVO.status}}】，说明【{{#reqVO.remark}}】",
            extra = "BUSINESS_OPERATION")
    public CommonResult<Boolean> handle(@Valid @RequestBody SafetyAccessAlertHandleReqVO reqVO) {
        alertService.handle(reqVO);
        return success(true);
    }
}
