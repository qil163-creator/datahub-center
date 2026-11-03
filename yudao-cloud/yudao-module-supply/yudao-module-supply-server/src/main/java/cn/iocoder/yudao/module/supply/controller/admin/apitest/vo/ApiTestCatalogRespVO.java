package cn.iocoder.yudao.module.supply.controller.admin.apitest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 平台内 HTTP 测试目录，不含 SQL 和连接配置")
@Data
public class ApiTestCatalogRespVO {

    private String publicBaseUrl;

    private List<Category> categories;

    private List<Api> apis;

    @Data
    public static class Category {

        private Long id;

        private Long parentId;

        private String name;
    }

    @Data
    public static class Api {

        private Long id;

        private Long categoryId;

        private String name;

        private String method;

        private String path;

        private String version;

        private String authType;

        private String configType;

        private String resultFormat;

        private Integer status;
    }
}
