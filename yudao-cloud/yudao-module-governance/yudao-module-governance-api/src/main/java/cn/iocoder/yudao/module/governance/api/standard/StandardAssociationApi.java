package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardAssociationColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermClientConfiguration;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** 当前标准落标事实，仅供认证资产服务聚合维护范围内的关联信息。 */
@FeignClient(name = ApiConstants.NAME, contextId = "standardAssociationApi",
        configuration = StandardTermClientConfiguration.class)
@Tag(name = "RPC 服务 - 标准关联信息")
public interface StandardAssociationApi {

    String PREFIX = ApiConstants.PREFIX + "/standard-association";

    /**
     * 获得标准当前已确认落标字段。
     */
    @GetMapping(PREFIX + "/columns")
    @Operation(summary = "获得标准当前已确认落标字段")
    CommonResult<List<StandardAssociationColumnRespDTO>> getColumns(
            @RequestParam("kind") @Pattern(regexp = "element|codeset") String kind,
            @RequestParam("id") @NotNull @Positive Integer id);
}
