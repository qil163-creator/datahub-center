package cn.iocoder.yudao.module.supply.controller.admin.apitest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - HTTP 接口测试请求")
@Data
public class ApiTestSendReqVO {

    @NotBlank(message = "请求模式不能为空")
    @Pattern(regexp = "REGISTERED|EXTERNAL", message = "请求模式只能为 REGISTERED 或 EXTERNAL")
    private String mode;

    @Schema(description = "登记 API 编号，REGISTERED 模式必填")
    private Long apiId;

    @Schema(description = "外部 HTTP 地址，仅 EXTERNAL 模式使用")
    @Size(max = 8_192, message = "URL 不能超过 8192 个字符")
    private String url;

    @NotBlank(message = "请求方式不能为空")
    @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS", message = "请求方式不支持")
    private String method;

    @Valid
    @Size(max = 128, message = "请求参数不能超过 128 项")
    private List<@NotNull ApiTestKeyValueVO> query = new ArrayList<>();

    @Valid
    @Size(max = 64, message = "请求头不能超过 64 项")
    private List<@NotNull ApiTestKeyValueVO> headers = new ArrayList<>();

    @Schema(description = "UTF-8 原始请求体；编码后最多 1 MiB，GET/HEAD 不接受请求体")
    @Size(max = 1_048_576, message = "请求体超过大小限制")
    private String body;

    @NotNull(message = "超时时间不能为空")
    @Min(value = 1, message = "超时时间至少为 1 秒")
    @Max(value = 120, message = "超时时间不能超过 120 秒")
    private Integer timeoutSeconds = 30;

    @NotNull(message = "重定向选项不能为空")
    private Boolean followRedirects = true;
}
