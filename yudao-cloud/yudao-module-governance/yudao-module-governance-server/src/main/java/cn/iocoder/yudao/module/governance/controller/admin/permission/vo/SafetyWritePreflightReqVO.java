package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "只读写权限预检，不执行 SQL，使用所选角色已保存的策略")
public class SafetyWritePreflightReqVO {

    @NotNull
    @Positive
    private Long databaseId;

    @NotNull
    @Positive
    private Long roleId;

    @NotBlank
    @Size(max = 20000)
    private String sql;

    @NotNull
    @Size(max = 5000)
    private List<@Size(max = 20000) String> parameterValues;
}
