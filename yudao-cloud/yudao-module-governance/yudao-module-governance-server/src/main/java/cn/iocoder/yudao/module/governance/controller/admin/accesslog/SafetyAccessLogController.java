package cn.iocoder.yudao.module.governance.controller.admin.accesslog;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo.SafetyAccessLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo.SafetyAccessLogRespVO;
import cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 敏感数据访问审计
 *
 * @author Li
 */
@Tag(name = "管理后台 - 敏感数据访问审计")
@RestController
@RequestMapping("/governance/safety-access-log")
@Validated
public class SafetyAccessLogController {

    @Resource
    private SafetyAccessLogService accessLogService;

    /**
     * 获得访问审计分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得访问审计分页")
    @PreAuthorize("@ss.hasPermission('governance:safety-access-log:query')")
    public CommonResult<PageResult<SafetyAccessLogRespVO>> getAccessLogPage(
            @Valid SafetyAccessLogPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(accessLogService.getAccessLogPage(pageReqVO),
                SafetyAccessLogRespVO.class));
    }

}
