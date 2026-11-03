package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.*;
import cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 接口请求试调")
@RestController
@RequestMapping("/dataforge/data-api")
@Validated
public class DataApiController {

    @Resource
    private DataApiService dataApiService;

    /**
     * 按表单配置试调 API，返回原始响应。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    @PostMapping("/test")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "按表单配置试调 API，返回原始响应")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:access:create', 'dataforge:access:update', 'dataforge:etl:save')")
    public CommonResult<String> testDataApi(@Valid @RequestBody DataApiSaveReqVO reqVO) {
        return success(dataApiService.testDataApi(reqVO));
    }

    /**
     * 按表单配置取数：解析行数据并推断字段。
     *
     * @param reqVO 请求参数
     * @return 数据 API获取响应数据
     */
    @PostMapping("/fetch-data")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "按表单配置取数：解析行数据并推断字段")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:access:create', 'dataforge:access:update', 'dataforge:etl:save')")
    public CommonResult<DataApiFetchRespVO> fetchData(@Valid @RequestBody DataApiSaveReqVO reqVO) {
        return success(dataApiService.fetchData(reqVO));
    }

}
