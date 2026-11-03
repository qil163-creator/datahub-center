package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 元数据检索 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据检索 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaSearchReqVO extends PageParam {

    @Schema(description = "关键字（同时匹配表名/字段名/业务名称/注释）", example = "人口")
    private String keyword;

    @Schema(description = "数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "库元数据编号", example = "1")
    private Long dbId;

    @Schema(description = "所属分层", example = "ODS")
    private String layer;

    @Schema(description = "主题域编号", example = "1")
    private Long subjectDomainId;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer publishStatus;

    @Schema(description = "对象状态", example = "1")
    private Integer objectStatus;

    @Schema(description = "仅看未认责（业务负责人为空）", example = "true")
    private Boolean ownerMissing;

}
