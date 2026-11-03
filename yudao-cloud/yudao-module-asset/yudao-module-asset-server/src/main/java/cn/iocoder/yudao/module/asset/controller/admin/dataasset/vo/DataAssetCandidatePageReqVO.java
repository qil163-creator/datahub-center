package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 待编目元数据分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAssetCandidatePageReqVO extends PageParam {

    @Schema(description = "关键字，同时匹配表名、业务名称和注释")
    private String keyword;

    @Schema(description = "是否只返回尚未编目挂接的表；目录追加技术关联时传 false")
    private Boolean onlyUnlinked;

}
