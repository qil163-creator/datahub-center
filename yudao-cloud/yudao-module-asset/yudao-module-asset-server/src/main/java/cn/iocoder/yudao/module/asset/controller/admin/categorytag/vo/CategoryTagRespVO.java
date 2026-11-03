package cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 标签类目 Response VO")
@Data
public class CategoryTagRespVO {

    private Long id;

    private Long parentId;

    private String categoryName;

    private String categoryCode;

    private Integer sort;

    private Integer status;

    private String remark;

    @Schema(description = "类目下直接挂接的标签数量")
    private Long tagCount;

    @Schema(description = "创建人")
    private String creator;

    private LocalDateTime createTime;

}
