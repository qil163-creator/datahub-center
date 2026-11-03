package cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 业务术语类目树节点")
@Data
public class StandardTermCategoryRespVO {
    private Long id;
    private Long parentId;
    private String categoryName;
    private Integer sort;
    private Short status;
}
