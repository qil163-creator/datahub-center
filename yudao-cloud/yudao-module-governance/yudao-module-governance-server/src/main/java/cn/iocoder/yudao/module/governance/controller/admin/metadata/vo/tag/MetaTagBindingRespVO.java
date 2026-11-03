package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 对象标签集合及其并发维护凭据。 */
@Data
@Schema(description = "管理后台 - 元数据对象标签绑定快照")
public class MetaTagBindingRespVO {

    @Schema(description = "对象类型：1-库 2-表 3-字段")
    private Integer objectType;

    @Schema(description = "对象编号")
    private Long objectId;

    @Schema(description = "标签绑定修订号")
    private Integer revision;

    @Schema(description = "已绑定标签编号")
    private List<Long> tagIds;

    @Schema(description = "已绑定标签")
    private List<MetaTagRespVO> tags;
}
