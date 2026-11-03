package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据标签 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据标签 Response VO")
@Data
public class MetaTagRespVO {

    @Schema(description = "标签编号", example = "1")
    private Long id;

    @Schema(description = "标签名称", example = "核心表")
    private String name;

    @Schema(description = "标签分类", example = "治理")
    private String category;

    @Schema(description = "标签颜色", example = "#1677ff")
    private String color;

    @Schema(description = "引用次数")
    private Long refCount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
