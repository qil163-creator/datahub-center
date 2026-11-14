package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 在线填报-附件 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报附件 Response VO")
@Data
public class FillAttachmentRespVO {

    @Schema(description = "附件编号（单元格保存的值）", example = "31")
    private Long id;

    @Schema(description = "文件名", example = "现场照片.jpg")
    private String name;

    @Schema(description = "内容类型", example = "image/jpeg")
    private String contentType;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long size;

}
