package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "管理后台 - API 在线签名 Request VO")
@Data
public class DataApiSignReqVO {

    @Schema(description = "应用 AppKey", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "AppKey 不能为空")
    private String appKey;

    @Schema(description = "应用秘钥 AppSecret", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "AppSecret 不能为空")
    private String appSecret;

    @Schema(description = "请求方式", requiredMode = Schema.RequiredMode.REQUIRED, example = "GET")
    @NotEmpty(message = "请求方式不能为空")
    private String method;

    @Schema(description = "接口完整路径（/services/{version}{path}）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "接口路径不能为空")
    private String apiPath;

    @Schema(description = "时间戳（毫秒）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "时间戳不能为空")
    private String timestamp;

    @Schema(description = "随机数 Nonce", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "Nonce 不能为空")
    private String nonce;

    @Schema(description = "原始查询串（当前 SM3 协议不纳入签名，仅用于联调记录）",
            example = "pageNo=1&pageSize=20")
    private String queryString;

    @Schema(description = "原始请求体（v1 不纳入签名；v2 按原始字节哈希）")
    private String body;

    @Schema(description = "签名协议版本：v1 或不传为旧协议，v2 为 HMAC-SM3", example = "v2")
    private String signVersion;

    @Schema(description = "凭证版本", example = "1")
    private String keyVersion;

    @Schema(description = "Content-Type（v2 纳入签名）", example = "application/json")
    private String contentType;

}
