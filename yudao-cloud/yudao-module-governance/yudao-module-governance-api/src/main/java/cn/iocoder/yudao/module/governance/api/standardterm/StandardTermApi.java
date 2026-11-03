package cn.iocoder.yudao.module.governance.api.standardterm;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 当前业务术语的平台内只读契约，用于资产等业务的实时引用。
 * 通过已认证 RPC 通道提供，不提供冻结版本语义。
 */
@FeignClient(name = ApiConstants.NAME, contextId = "standardTermApi",
        configuration = StandardTermClientConfiguration.class)
@Tag(name = "RPC 服务 - 业务术语")
public interface StandardTermApi {

    String PREFIX = ApiConstants.PREFIX + "/standard-term";

    /**
     * 检索平台内启用术语，按名称或别名匹配，最多 50 条。
     */
    @GetMapping(PREFIX + "/enabled-list")
    @Operation(summary = "检索平台内启用术语，按名称或别名匹配，最多 50 条")
    CommonResult<List<StandardTermRespDTO>> getEnabledTermList(
            @RequestParam(value = "keyword", required = false) @Size(max = 100) String keyword);

    /**
     * 批量读取平台内当前术语，含停用术语，删除或不可见的编号不返回。
     */
    @GetMapping(PREFIX + "/list")
    @Operation(summary = "批量读取平台内当前术语，含停用术语，删除或不可见的编号不返回")
    CommonResult<List<StandardTermRespDTO>> getTermList(
            @RequestParam("ids") @NotEmpty @Size(max = 200) List<@NotNull @Positive Long> ids);

}
