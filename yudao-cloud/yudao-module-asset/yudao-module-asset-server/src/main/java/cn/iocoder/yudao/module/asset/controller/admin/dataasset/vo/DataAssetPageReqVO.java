package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据资产分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAssetPageReqVO extends PageParam {

    @Schema(description = "关键字，同时匹配资产名、表名、说明")
    private String keyword;

    @Schema(description = "资产类目编号，包含其全部下级")
    private Long categoryId;

    @Schema(description = "资产类型：1-数据库表 2-API 3-文件")
    private Integer assetType;

    @Schema(description = "发布状态：0-未发布 1-已发布 2-已下线")
    private Integer status;

    private Long datasourceId;

    private String layer;

    private Long subjectDomainId;

    private Long tagId;

}
