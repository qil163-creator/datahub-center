package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据查询文件目录 Response VO")
@Data
public class DataQueryFileRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "父节点编号")
    private Long parentId;

    @Schema(description = "节点类型：1-目录 2-SQL 脚本")
    private Integer nodeType;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "SQL 内容，仅详情接口返回")
    private String sqlText;

    @Schema(description = "关联数据源编号")
    private Long datasourceId;

    @Schema(description = "最大返回行数")
    private Integer queryLimit;

    @Schema(description = "可见范围：1-公开 2-私有")
    private Integer visibility;

    @Schema(description = "是否当前用户创建，仅创建人可改删")
    private Boolean owned;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
