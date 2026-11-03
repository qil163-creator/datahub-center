package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 远程文件条目")
public class FileResourceEntryRespVO {

    private String name;

    private String path;

    private boolean directory;

    private String type;

    private Long size;

    @Schema(description = "源端最后修改时间，毫秒时间戳")
    private Long updateTime;
}
