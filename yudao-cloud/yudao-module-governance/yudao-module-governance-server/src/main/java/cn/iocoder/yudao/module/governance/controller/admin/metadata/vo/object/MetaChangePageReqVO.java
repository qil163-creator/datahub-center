package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 元数据变更明细分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据变更明细分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaChangePageReqVO extends PageParam {

    @Schema(description = "归属表编号", example = "1")
    private Long tableId;

    @Schema(description = "归属库编号", example = "1")
    private Long dbId;

    @Schema(description = "采集实例编号", example = "1")
    private Long instanceId;

    @Schema(description = "对象类型：1-库 2-表 3-字段", example = "3")
    private Integer objectType;

    @Schema(description = "变更类型：1-新增 2-修改 3-删除", example = "2")
    private Integer changeType;

    @Schema(description = "变更来源：1-采集 2-手工 3-导入 4-回滚", example = "1")
    private Integer changeSource;

    @Schema(description = "变更属性，如 COMMENT/DATA_TYPE", example = "COMMENT")
    private String property;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
