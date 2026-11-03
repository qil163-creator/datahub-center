package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 元数据标签分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据标签分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MetaTagPageReqVO extends PageParam {

    @Schema(description = "标签名称，模糊匹配", example = "核心")
    private String name;

    @Schema(description = "标签分类", example = "治理")
    private String category;

}
