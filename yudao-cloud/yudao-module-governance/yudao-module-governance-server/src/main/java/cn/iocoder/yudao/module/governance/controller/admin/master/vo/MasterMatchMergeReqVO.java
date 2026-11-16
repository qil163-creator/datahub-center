package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 提交疑似重复组合并")
@Data
public class MasterMatchMergeReqVO {

    @NotNull(message = "疑似重复组编号不能为空")
    private Long groupId;

    @Schema(description = "幂等请求标识（UUID）")
    @NotBlank(message = "请求标识不能为空")
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            message = "请求标识不正确")
    private String requestId;

    @Schema(description = "保留记录（黄金记录）的主键标识")
    @NotBlank(message = "请选择保留记录")
    private String survivorKey;

    @Schema(description = "合并进保留记录并删除的成员主键标识")
    @NotNull(message = "请选择被合并的记录")
    @Size(min = 1, max = 19, message = "被合并的记录为1至19条")
    private List<String> mergedKeys;

    @Schema(description = "黄金记录各字段的值，每个值须取自参与合并的某条记录")
    @NotNull(message = "请确认黄金记录字段值")
    private Map<String, Object> values;
}
