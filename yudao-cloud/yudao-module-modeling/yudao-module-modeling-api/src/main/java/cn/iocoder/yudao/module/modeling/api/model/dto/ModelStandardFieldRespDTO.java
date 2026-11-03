package cn.iocoder.yudao.module.modeling.api.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 绑定了数据标准的模型字段 响应 DTO（供治理模块做设计态落标）
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 绑定数据标准的模型字段 Response DTO")
@Data
public class ModelStandardFieldRespDTO {

    @Schema(description = "物理模型编号", example = "1")
    private Long modelId;

    /** 当前模型生命周期状态。 */
    private String modelStatus;

    @Schema(description = "统一设计编号；modelId仍保留原部署绑定身份")
    private Long designModelId;

    @Schema(description = "物理模型名称", example = "人员信息模型")
    private String modelName;

    private String description;
    private String dataDomainName;
    private String subjectDomainName;
    private String sourceInfo;
    private String creator;
    private java.time.LocalDateTime createTime;
    private Integer enabled;
    private String tableType;

    @Schema(description = "物理表名", example = "t_person")
    private String tableName;




    @Schema(description = "字段稳定标识", example = "3f2b8c1e-…")
    private String fieldUid;

    @Schema(description = "字段名称", example = "person_name")
    private String fieldName;

    @Schema(description = "字段中文名称", example = "姓名")
    private String cnName;

    @Schema(description = "数据类型", example = "varchar")
    private String dataType;

    @Schema(description = "数据长度", example = "64")
    private Integer dataLength;

    @Schema(description = "绑定的标准数据元编号", example = "1024")
    private Long standardId;

}
