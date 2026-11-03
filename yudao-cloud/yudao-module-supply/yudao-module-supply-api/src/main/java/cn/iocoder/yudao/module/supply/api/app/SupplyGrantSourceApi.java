package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.supply.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 与旧应用授权协议并存；服务端必须认证服务身份，并校验命令声明的来源系统。
 * 客户端固定使用 {@link SupplyGrantSourceClientConfiguration} 的双向 TLS 通道，调用方须配置对应 SSL bundle。
 */
@FeignClient(name = ApiConstants.NAME, contextId = "supply-grant-source-v2",
        configuration = SupplyGrantSourceClientConfiguration.class)
@Tag(name = "RPC 服务 - 来源级授权 v2")
public interface SupplyGrantSourceApi {

    String PREFIX = ApiConstants.PREFIX + "/grant-source/v2";

    /**
     * 应用来源授权命令。
     *
     * @param command 待处理命令
     * @return 供应授权来源结果
     */
    @PostMapping(PREFIX + "/command")
    @Operation(summary = "应用来源授权命令")
    CommonResult<SupplyGrantSourceResultDTO> applySource(@Valid @RequestBody SupplyGrantSourceCommandDTO command);

    /**
     * 查询当前平台的来源操作回执。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权来源结果
     */
    @GetMapping(PREFIX + "/operation")
    @Operation(summary = "查询当前平台的来源操作回执")
    CommonResult<SupplyGrantSourceResultDTO> querySourceOperation(
            @NotBlank @Size(max = 64) @RequestParam("sourceSystem") String sourceSystem,
            @NotBlank @Size(max = 128) @RequestParam("operationId") String operationId);
}
