package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 表元数据分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表元数据分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaTablePageReqVO extends PageParam {

    /** 元数据树中“未指定来源系统”的虚拟节点，不作为业务表中的系统编号存储。 */
    public static final long UNASSIGNED_SOURCE_SYSTEM_ID = -1L;

    @Schema(description = "仅有定版镜像的表")
    private Boolean finalized;

    @Schema(description = "来源系统编号；省略查询全部，-1仅查询未指定来源系统（数据库NULL）")
    private Long sourceSystemId;


    @Schema(description = "库元数据编号", example = "1")
    private Long dbId;

    @Schema(description = "数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "表名，模糊匹配", example = "dwd_dim_station")
    private String tableName;

    @Schema(description = "注释关键字（同时匹配源注释/平台注释/业务名称）", example = "站点")
    private String comment;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer publishStatus;

    @Schema(description = "对象状态：1-正常 2-待确认删除 3-已删除（不传默认过滤已删除）", example = "1")
    private Integer objectStatus;

    @Schema(description = "主题域编号", example = "1")
    private Long subjectDomainId;

}
