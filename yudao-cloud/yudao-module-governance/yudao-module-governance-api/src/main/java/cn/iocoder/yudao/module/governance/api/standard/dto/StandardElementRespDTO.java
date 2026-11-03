package cn.iocoder.yudao.module.governance.api.standard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 标准数据元精简信息 响应 DTO（供建模等模块比对标准）
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 标准数据元精简信息 Response DTO")
@Data
public class StandardElementRespDTO {

    @Schema(description = "数据元编号", example = "1")
    private Integer id;

    @Schema(description = "数据元编码", example = "PERSON_NAME")
    private String code;

    @Schema(description = "中文名称", example = "姓名")
    private String name;

    @Schema(description = "英文名称", example = "personName")
    private String engName;

    @Schema(description = "字段类型", example = "VARCHAR")
    private String columnType;

    @Schema(description = "数据长度（字符/字节数上限）", example = "64")
    private Integer dataLength;

    @Schema(description = "数据精度（数值总位数）", example = "18")
    private Integer dataPrecision;

    @Schema(description = "小数位数", example = "2")
    private Integer dataScale;

    @Schema(description = "数据格式，如 yyyy-MM-dd", example = "yyyy-MM-dd")
    private String dataFormat;

    @Schema(description = "计量单位", example = "元")
    private String measureUnit;

    @Schema(description = "值域范围说明", example = "[0,150]")
    private String valueRange;

    @Schema(description = "是否可为空：0-不可空 1-可空", example = "1")
    private Integer nullableFlag;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Short status;

}
