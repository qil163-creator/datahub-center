package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 数据库连接分组诊断 Request VO")
@Data
public class DatabaseDiagnosisReqVO {

    @Schema(description = "已保存的数据源编号，与 connection 恰好填写一个")
    @Positive(message = "数据源编号必须为正数")
    private Long databaseId;

    @Schema(description = "待测试的表单连接；编辑时密码留空仅允许复用未改变连接配置的原密码")
    @Valid
    @ToString.Exclude
    private DatabaseSaveReqVO connection;

    @Schema(description = "是否授权在独立临时表中检查写入能力，默认关闭")
    private boolean includeWriteChecks;

    /**
     * 判断是否满足来源有效条件。
     *
     * @return 判断结果
     */
    @AssertTrue(message = "databaseId 与 connection 必须且只能填写一个")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isSourceValid() {
        return (databaseId != null) != (connection != null);
    }
}
