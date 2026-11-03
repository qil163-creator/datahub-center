package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据库类型定义分页 Request VO")
@Data
public class DatabaseTypePageReqVO extends PageParam {

    @Schema(description = "数据源编码")
    private String typeCode;

    @Schema(description = "数据源名称", example = "王五")
    private String typeName;

    @Schema(description = "连接分类")
    private String categoryCode;

    @Schema(description = "连接协议类型", example = "2")
    private String protocolType;

    @Schema(description = "状态", example = "2")
    private Short status;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
