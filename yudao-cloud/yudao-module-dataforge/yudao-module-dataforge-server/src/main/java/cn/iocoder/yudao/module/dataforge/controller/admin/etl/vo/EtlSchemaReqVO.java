package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "管理后台 - ETL 字段契约推导 Request VO（未保存的画布也可提交）")
@Data
public class EtlSchemaReqVO {

    @Schema(description = "连接环境编号")
    @jakarta.validation.constraints.Positive
    private Long environmentId;

    @Schema(description = "画布节点（LogicFlow nodes）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "节点不能为空")
    @jakarta.validation.constraints.Size(max = 32, message = "轻量 ETL 最多32个节点")
    private List<Map<String, Object>> nodes;

    @Schema(description = "画布连线（LogicFlow edges）")
    @jakarta.validation.constraints.Size(max = 128, message = "轻量 ETL 最多128条连线")
    private List<Map<String, Object>> edges;

    @Schema(description = "未保存画布的参数定义；预览、校验和 Schema 推导按此定义验证")
    @Valid
    @jakarta.validation.constraints.Size(max = 100)
    private List<EtlPipelineSaveReqVO.ParamItem> parameterDefinitions;

    @Schema(description = "本次预览/校验使用的参数值，值均以字符串传输")
    @jakarta.validation.constraints.Size(max = 100)
    @JsonDeserialize(contentUsing = StrictJsonStringDeserializer.class)
    private Map<@jakarta.validation.constraints.Size(max = 64) String,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 4096) String> parameters;

}
