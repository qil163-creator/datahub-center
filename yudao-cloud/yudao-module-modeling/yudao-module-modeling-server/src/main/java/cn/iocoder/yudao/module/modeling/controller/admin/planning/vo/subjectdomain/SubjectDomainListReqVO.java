package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 主题域列表 Request VO")
@Data
public class SubjectDomainListReqVO {

    @Schema(description = "主题域名称（模糊）", example = "水文")
    private String name;

    private Long dataDomainId;

    private Long parentId;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
