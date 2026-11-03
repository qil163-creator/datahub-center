package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 问题工单分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityWorkOrderPageReqVO extends PageParam {

    private String orderName;

    private String taskName;

    private String orderStatus;

    private String responsibilityStatus;

    private Long ownerUserId;

    private Long handlingDeptId;

    private String closeType;

    private String problemType;

    private String severity;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
