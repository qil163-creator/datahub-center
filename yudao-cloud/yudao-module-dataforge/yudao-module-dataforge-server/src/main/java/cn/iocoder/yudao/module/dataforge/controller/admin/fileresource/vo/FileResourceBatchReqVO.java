package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class FileResourceBatchReqVO {

    @NotNull(message = "请选择数据连接")
    private Long connectionId;

    @NotEmpty
    @Size(max = 100)
    private List<@NotBlank @Size(max = 4096) String> paths;
}
