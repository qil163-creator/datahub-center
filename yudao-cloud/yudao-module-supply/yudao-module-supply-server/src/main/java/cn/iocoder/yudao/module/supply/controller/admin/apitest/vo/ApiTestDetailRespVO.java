package cn.iocoder.yudao.module.supply.controller.admin.apitest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 已发布 API 的最小测试定义，不含 SQL 和连接配置")
@Data
public class ApiTestDetailRespVO {

    private Long id;

    private String name;

    private String method;

    private String path;

    private String version;

    private String authType;

    private String configType;

    private String resultFormat;

    private Integer status;

    private String publicUrl;

    private List<Param> requestParams;

    @Data
    public static class Param {

        private String name;

        private String paramType;

        private String defaultValue;

        private String exampleValue;

        private Integer nullable;

        private String description;
    }
}
