package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - API 服务精简 Response VO（下拉/树选择，不含 SQL 与连接配置）")
@Data
public class DataApiSimpleRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "API 名称", example = "站点流量实时数据服务")
    private String name;

    @Schema(description = "服务分类编号", example = "1")
    private Long categoryId;

    @Schema(description = "对外版本", example = "v1")
    private String version;

    @Schema(description = "状态：0-下线 1-上线", example = "1")
    private Integer status;

    @Schema(description = "请求方式", example = "GET")
    private String method;

    @Schema(description = "相对路径", example = "/orders")
    private String path;

    private Long providerDeptId;

    private Long activeReleaseId;

    private Integer draftRevision;

    private Boolean unpublishedDraft;
}
