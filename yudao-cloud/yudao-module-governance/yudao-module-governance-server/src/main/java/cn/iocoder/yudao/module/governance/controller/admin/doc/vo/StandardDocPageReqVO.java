package cn.iocoder.yudao.module.governance.controller.admin.doc.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 标准文件登记分页 Request VO")
@Data
public class StandardDocPageReqVO extends PageParam {

    @Schema(description = "标准编号")
    private String stdCode;

    @Schema(description = "标准名称")
    private String nameCn;

    @Schema(description = "标准英文名称（已废弃）")
    private String nameEn;

    @Schema(description = "标准分类", example = "1")
    private String stdType;

    @Schema(description = "标准类目编号，包含全部子孙类目")
    private Long categoryId;

    @Schema(hidden = true)
    private java.util.List<Long> categoryIds;

    @Schema(description = "发布机构")
    private String publishOrg;

    @Schema(description = "入库编号")
    private String registryNo;

    @Schema(description = "入库时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] registryTime;

    @Schema(description = "实施状态", example = "1")
    private String implementStatus;

    @Schema(description = "状态", example = "2")
    private Short status;

}
