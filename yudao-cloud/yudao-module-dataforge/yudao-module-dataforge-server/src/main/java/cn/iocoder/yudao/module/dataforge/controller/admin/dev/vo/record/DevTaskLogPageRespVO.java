package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 任务实例日志分页；调度器句柄不向浏览器暴露")
public class DevTaskLogPageRespVO {

    private String message;

    private Integer readLines;

    private Integer nextSkipLineNum;

    @Schema(description = "可能继续产生日志；false不代表引擎执行成功")
    private Boolean mayHaveMore;

    private Boolean truncated;

    private Boolean available;

    private String source;

    private String statusMessage;
}
