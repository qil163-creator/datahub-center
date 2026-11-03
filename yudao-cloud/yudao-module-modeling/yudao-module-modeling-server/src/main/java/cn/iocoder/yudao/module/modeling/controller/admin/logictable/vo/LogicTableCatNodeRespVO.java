package cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "管理后台 - 逻辑表类目树节点 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogicTableCatNodeRespVO {

    @Schema(description = "节点键，类型前缀加业务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "domain-29")
    private String key;

    @Schema(description = "节点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水利监测与防汛调度")
    private String name;

    @Schema(description = "节点类型：GROUP/LAYER/SOURCE_SYSTEM/DATA_DOMAIN/SUBJECT_DOMAIN", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DATA_DOMAIN")
    private String type;

    @Schema(description = "来源系统编号", example = "4")
    private Long sourceSystemId;

    @Schema(description = "数据板块编号", example = "29")
    private Long dataDomainId;

    @Schema(description = "主题域编号", example = "113")
    private Long subjectDomainId;

    @Schema(description = "子节点")
    private List<LogicTableCatNodeRespVO> children;

    @Schema(description = "限定表类型（发布模型按数仓分层浏览时使用）", example = "DIMENSION")
    private String tableType;

}
