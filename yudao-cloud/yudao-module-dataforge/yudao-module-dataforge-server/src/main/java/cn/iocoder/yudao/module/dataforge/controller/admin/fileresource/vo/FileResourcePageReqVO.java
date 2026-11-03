package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileResourcePageReqVO extends PageParam {

    @NotNull(message = "请选择数据连接")
    private Long connectionId;

    @NotBlank
    @Size(max = 4096)
    private String path = "/";

    @Size(max = 255)
    private String name;

    @Pattern(regexp = "[A-Za-z0-9_-]{0,30}")
    private String type;

    @Pattern(regexp = "asc|desc")
    private String order;
}
