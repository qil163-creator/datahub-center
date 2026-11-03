package cn.iocoder.yudao.module.dataforge.controller.admin.integration;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationFileSchemaModels.*;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationFileSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
@RequestMapping("/dataforge/integration")
@Tag(name = "管理后台 - 集成文件字段解析")
public class IntegrationFileSchemaController {
    @Resource private IntegrationFileSchemaService service;

    /** 解析当前用户自己的上传文件，不运行任务也不返回文件数据行。 */
    @PostMapping("/file-schema")
    @Operation(summary = "只读采样上传文件的字段")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:integration:create', 'dataforge:integration:update')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Response> schema(@Valid @RequestBody Request request) {
        return success(service.parse(request));
    }
}
