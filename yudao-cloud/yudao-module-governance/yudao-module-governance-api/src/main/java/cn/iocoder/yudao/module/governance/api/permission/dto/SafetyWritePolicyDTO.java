package cn.iocoder.yudao.module.governance.api.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "独立 SQL 写授权；空配置不授予写权限，既有读写枚举不代替此策略")
public class SafetyWritePolicyDTO {

    @NotNull
    @Size(min = 1, max = 3)
    private List<String> operations;

    @NotNull
    @Size(max = 2000)
    private List<String> writeColumns;

    @NotNull
    @Size(max = 2000)
    private List<String> filterColumns;

    @NotNull
    @Size(max = 20)
    @Schema(description = "精确物理列到等值文本的映射，各列 AND；null 表示 IS NULL；不接受 SQL 表达式")
    private Map<String, String> rowScope;

    @NotNull
    @Schema(description = "显式允许全部行；与非空 rowScope 互斥")
    private Boolean unrestrictedRows;
}
