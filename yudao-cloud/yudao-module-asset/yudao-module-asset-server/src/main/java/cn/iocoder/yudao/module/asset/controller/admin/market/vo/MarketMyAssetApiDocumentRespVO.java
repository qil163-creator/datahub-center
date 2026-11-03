package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 本人批准范围内的已发布 API 文档，不包含应用密钥或服务内部配置")
@Data
public class MarketMyAssetApiDocumentRespVO {

    private Long applyId;

    private Long apiId;

    private String name;

    private String path;

    private String method;

    private String version;

    private List<RequestParam> requestParams;

    private List<ResponseField> responseFields;

    @Data
    public static class RequestParam {

        private String name;

        private String type;

        private boolean required;

        private String description;
    }

    @Data
    public static class ResponseField {

        private String name;

        private String type;

        private String description;
    }
}
