package cn.iocoder.yudao.module.supply.controller.admin.apitest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "管理后台 - HTTP 接口测试真实响应")
@Data
public class ApiTestSendRespVO {

    private Integer statusCode;

    private String statusText;

    private List<ApiTestKeyValueVO> headers;

    private String body;

    @Schema(description = "UTF8 或 BASE64")
    private String bodyEncoding;

    @Schema(description = "已读取响应体字节数，最多 2 MiB")
    private Integer bodyBytes;

    private Long durationMs;

    private Boolean truncated;

    private String url;

    private List<Redirect> redirectChain;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Redirect {

        private String url;

        private Integer statusCode;
    }
}
