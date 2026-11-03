package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardReferenceDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.*;

import java.util.List;

@FeignClient(name = ApiConstants.NAME, contextId = "standardReferenceApi")
public interface StandardReferenceApi {

    String PREFIX = ApiConstants.PREFIX + "/standard-reference";

    /**
     * 检索平台内可见术语、标准数据元和已发布来源，最多50个对象。
     */
    @GetMapping(PREFIX + "/search")
    @Operation(summary = "检索平台内可见术语、标准数据元和已发布来源，最多50个对象")
    CommonResult<List<StandardReferenceDTO>> search(@RequestParam @Pattern(regexp = "TERM|STANDARD|SOURCE") String kind,
            @RequestParam(required = false) @Size(max = 100) String keyword);

    /**
     * 验证指定外部引用及其内容标识。
     */
    @GetMapping(PREFIX + "/resolve")
    @Operation(summary = "验证指定外部引用及其内容标识")
    CommonResult<StandardReferenceDTO> resolve(@RequestParam @Pattern(regexp = "TERM|STANDARD|SOURCE") String kind,
            @RequestParam @Positive Long id, @RequestParam @NotBlank @Size(max = 100) String version);
}
