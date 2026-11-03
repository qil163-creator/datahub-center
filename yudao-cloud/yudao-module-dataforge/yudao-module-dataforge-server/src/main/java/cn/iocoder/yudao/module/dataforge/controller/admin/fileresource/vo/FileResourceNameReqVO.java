package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileResourceNameReqVO extends FileResourcePathReqVO {

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最多50个字符")
    private String name;
}
