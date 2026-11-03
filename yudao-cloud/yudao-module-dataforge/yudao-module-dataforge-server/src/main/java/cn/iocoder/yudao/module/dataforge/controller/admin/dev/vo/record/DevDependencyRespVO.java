package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "管理后台 - 运行实例的版本化依赖状态")
public class DevDependencyRespVO {

    private Long taskId;

    private String taskName;

    private Long requiredVersionId;

    private LocalDate businessDate;

    private Long recordId;

    private Long actualVersionId;

    private String status;

    private String qualityStatus;

    private Boolean satisfied;

    private String reason;
}
