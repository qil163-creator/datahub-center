package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主数据相似匹配执行记录")
@Data
public class MasterMatchRunRespVO {

    private Long id;

    private Long modelId;

    @Schema(description = "状态：RUNNING 执行中、SUCCESS 成功、FAILED 失败")
    private String status;

    private Integer scannedRows;

    private Long comparedPairs;

    @Schema(description = "新增疑似重复组数")
    private Integer groupCount;

    @Schema(description = "执行说明或失败原因")
    private String message;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String runByName;
}
