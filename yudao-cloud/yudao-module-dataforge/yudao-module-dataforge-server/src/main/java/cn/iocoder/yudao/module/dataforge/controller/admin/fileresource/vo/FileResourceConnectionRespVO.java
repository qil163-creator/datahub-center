package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 文件连接，无凭据")
public class FileResourceConnectionRespVO {

    private Long id;

    private String name;

    private String type;

    private String address;

    private Integer status;

    private String rootPath;

    private boolean supportsOverwrite = true;
}
