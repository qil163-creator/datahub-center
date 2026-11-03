package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.Pattern;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据板块分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataDomainPageReqVO extends PageParam {

    @Schema(description = "数据板块名称（模糊）", example = "水质监测")
    private String name;

    private Long leaderUserId;

    @Pattern(regexp = "id|createTime")
    private String sortField;

    @Pattern(regexp = "asc|desc")
    private String sortOrder;

    @Schema(description = "英文缩写（模糊）", example = "WQM")
    private String code;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
