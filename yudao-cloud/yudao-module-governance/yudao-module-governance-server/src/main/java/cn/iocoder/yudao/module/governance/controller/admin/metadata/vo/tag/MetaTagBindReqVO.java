package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 元数据对象绑定标签 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据对象绑定标签 Request VO")
@Data
public class MetaTagBindReqVO {

    @Schema(description = "对象类型：1-库 2-表 3-字段", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "对象类型不能为空")
    @Min(1)
    @Max(3)
    private Integer objectType;

    @Schema(description = "对象编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    @NotNull(message = "对象编号不能为空")
    private Long objectId;

    @Schema(description = "读取标签绑定快照时的修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "标签修订号不能为空，请重新加载标签")
    @Min(0)
    private Integer revision;

    @Schema(description = "标签编号列表（全量覆盖该对象已绑标签，空列表表示清空）")
    @NotNull(message = "标签列表不能为空，清空标签请明确提交空数组")
    @Size(max = 200, message = "一个对象最多绑定 200 个标签")
    private List<@NotNull Long> tagIds;

}
