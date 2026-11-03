package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 元数据采集实例分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集实例分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaCollectInstancePageReqVO extends PageParam {

    private String name;

    private Long datasourceId;

    private Long sourceSystemId;

    private Integer collectType;

    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private java.time.LocalDateTime[] startTime;


    @Schema(description = "采集任务编号", example = "1")
    private Long taskId;

    @Schema(description = "状态：1-运行中 2-成功 3-失败 4-部分成功", example = "2")
    private Integer status;

    @Schema(description = "触发方式：1-调度 2-手动 3-事件 4-导入 5-手工登记", example = "1")
    private Integer triggerType;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
