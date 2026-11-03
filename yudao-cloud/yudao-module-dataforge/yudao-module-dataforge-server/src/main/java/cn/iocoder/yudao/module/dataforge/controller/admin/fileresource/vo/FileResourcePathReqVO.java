package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class FileResourcePathReqVO {

    @NotNull(message = "请选择数据连接")
    private Long connectionId;

    @NotBlank(message = "路径不能为空")
    @Size(max = 4096)
    private String path = "/";
}
