package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "管理后台 - ETL Java 脚本编译检查 Request VO")
@Data
public class EtlJavaCompileReqVO {

    @Schema(description = "脚本源码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "脚本代码不能为空")
    private String code;

}
