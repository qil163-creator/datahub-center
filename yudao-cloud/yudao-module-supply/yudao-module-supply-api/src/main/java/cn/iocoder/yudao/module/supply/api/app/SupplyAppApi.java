package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppApiGrantReqDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantOpRespDTO;
import cn.iocoder.yudao.module.supply.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 数据服务应用 RPC：供资产申请大厅开通 API 订阅。
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 数据服务应用")
public interface SupplyAppApi {

    String PREFIX = ApiConstants.PREFIX + "/app";

    /**
     * 为应用授权订阅 API。
     *
     * @param reqDTO 请求参数
     * @return 供应应用
     */
    @PostMapping(PREFIX + "/grant-api")
    @Operation(summary = "为应用授权订阅 API")
    CommonResult<Long> grantAppApi(@Valid @RequestBody SupplyAppApiGrantReqDTO reqDTO);

    /**
     * 取消应用对 API 的授权。
     *
     * @param reqDTO 请求参数
     * @return 供应应用
     */
    @PostMapping(PREFIX + "/revoke-api")
    @Operation(summary = "取消应用对 API 的授权")
    CommonResult<Boolean> revokeAppApi(@Valid @RequestBody SupplyAppApiGrantReqDTO reqDTO);

    /**
     * 按创建人列出应用。
     *
     * @param userId 用户编号
     * @return 按创建人列出应用结果集合
     */
    @GetMapping(PREFIX + "/list-by-creator")
    @Operation(summary = "按创建人列出应用")
    CommonResult<List<SupplyAppRespDTO>> listAppsByCreator(@RequestParam("userId") Long userId);

    /**
     * 查询授权交付结果。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权操作响应数据
     */
    @GetMapping(PREFIX + "/grant-op")
    @Operation(summary = "查询授权交付结果")
    CommonResult<SupplyGrantOpRespDTO> getGrantOperation(@RequestParam("sourceSystem") String sourceSystem,
            @RequestParam("operationId") String operationId);

}
