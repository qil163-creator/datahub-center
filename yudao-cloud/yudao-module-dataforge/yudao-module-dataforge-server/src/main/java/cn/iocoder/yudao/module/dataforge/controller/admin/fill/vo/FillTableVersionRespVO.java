package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-模板版本 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 模板版本 Response VO")
@Data
public class FillTableVersionRespVO {

    @Schema(description = "版本编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "填报表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long tableId;

    @Schema(description = "版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer versionNo;

    @Schema(description = "模板名称快照", example = "民生项目月报")
    private String name;

    @Schema(description = "状态：1-已发布 2-已停用", example = "1")
    private Integer status;

    @Schema(description = "字段结构 JSON")
    private String fieldSchema;

    @Schema(description = "布局快照 JSON")
    private String layoutSnapshot;

    @Schema(description = "公式定义 JSON")
    private String formulaSchema;

    @Schema(description = "校验规则 JSON")
    private String validationSchema;

    @Schema(description = "目标映射 JSON")
    private String targetMapping;

    @Schema(description = "合并策略", example = "UPSERT")
    private String mergeStrategy;

    @Schema(description = "业务键字段（英文逗号分隔）", example = "org_code,period")
    private String businessKeyFields;

    @Schema(description = "发布内容摘要（SHA-256）")
    private String contentHash;

    @Schema(description = "发布人编号")
    private Long releaseUserId;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
