package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - ETL 工具箱组件 Response VO")
@Data
public class EtlToolRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "读取数据库表")
    private String name;

    @Schema(description = "图标", example = "carbon:data-base")
    private String icon;

    @Schema(description = "类型：-1 目录 1 输入 2 转换 3 输出 4 脚本", example = "1")
    private Integer type;

    @Schema(description = "组件代码", example = "read-db")
    private String code;

    @Schema(description = "是否内置：0-否 1-是", example = "1")
    private Integer builtIn;

    @Schema(description = "是否启用：0-禁用 1-启用", example = "1")
    private Integer enabled;

    @Schema(description = "排序号", example = "1")
    private Integer orderNo;

    @Schema(description = "描述", example = "按整表或 SQL 读取数据库数据")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
