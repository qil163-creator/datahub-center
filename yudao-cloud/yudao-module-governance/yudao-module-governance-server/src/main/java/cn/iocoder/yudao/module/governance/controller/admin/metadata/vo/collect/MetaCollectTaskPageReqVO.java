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
 * 元数据采集任务分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaCollectTaskPageReqVO extends PageParam {

    private Integer collectType;

    private Integer scheduleEnabled;

    @Schema(description = "任务名称，模糊匹配", example = "数据中台应用库")
    private String name;

    @Schema(description = "数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "来源系统编号", example = "1")
    private Long sourceSystemId;

    @Schema(description = "任务上下线：0-下线 1-上线", example = "1")
    private Integer enabled;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
