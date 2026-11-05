package cn.iocoder.yudao.module.supply.controller.admin.apitest;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestCatalogRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestDetailRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendRespVO;
import cn.iocoder.yudao.module.supply.service.apitest.ApiTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - HTTP 接口测试")
@RestController
@RequestMapping("/supply/api-test")
@Validated
public class ApiTestController {

    @Resource
    private ApiTestService apiTestService;

    /**
     * 获得平台内已上线 API 测试目录。
     *
     * @return 目录
     */
    @GetMapping("/catalog")
    @Operation(summary = "获得平台内已上线 API 测试目录")
    @PreAuthorize("@ss.hasPermission('supply:data-api:invoke')")
    public CommonResult<ApiTestCatalogRespVO> getCatalog() {
        return success(apiTestService.getCatalog());
    }

    /**
     * 获得已发布 API 的最小测试定义。
     *
     * @param id 记录编号
     * @return API测试详情响应数据
     */
    @GetMapping("/detail")
    @Operation(summary = "获得已发布 API 的最小测试定义")
    @Parameter(name = "id", description = "API 编号", required = true)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('supply:data-api:invoke')")
    public CommonResult<ApiTestDetailRespVO> getDetail(@RequestParam("id") Long id) {
        return success(apiTestService.getDetail(id));
    }

    /**
     * 发送登记 API 或公开 HTTP 地址的真实测试请求。
     *
     * @param reqVO 请求参数
     * @return API测试发送响应数据
     */
    @LogRecord(type = "数据服务-HTTP 接口测试", subType = "发起真实接口测试请求", bizNo = "{{(#reqVO.apiId) ?: 0}}",
            success = "发起了真实接口测试请求，平台API编号【{{#reqVO.apiId}}】，模式【{{#reqVO.mode}}】，请求方式【{{#reqVO.method}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/send")
    @Operation(summary = "发送登记 API 或公开 HTTP 地址的真实测试请求")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('supply:data-api:invoke')")
    public CommonResult<ApiTestSendRespVO> send(@Valid @RequestBody ApiTestSendReqVO reqVO) {
        return success(apiTestService.send(reqVO));
    }
}
