package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterChangeLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterChangeLogRespVO;
import cn.iocoder.yudao.module.governance.service.master.MasterDataChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据变更日志")
@RestController
@RequestMapping("/governance/master-change-log")
@Validated
public class MasterChangeLogController {

    @Resource
    private MasterDataChangeService service;

    /**
     * 查询已生效的逐字段变更日志。
     */
    @GetMapping("/page")
    @Operation(summary = "查询已生效的逐字段变更日志")
    @PreAuthorize("@ss.hasPermission('governance:master-change-log:query')")
    public CommonResult<PageResult<MasterChangeLogRespVO>> page(@Valid MasterChangeLogPageReqVO req) {
        return success(service.logs(req));
    }
}
