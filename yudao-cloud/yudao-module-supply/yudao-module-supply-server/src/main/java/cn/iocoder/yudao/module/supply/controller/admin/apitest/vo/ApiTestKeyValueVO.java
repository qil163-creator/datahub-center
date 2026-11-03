package cn.iocoder.yudao.module.supply.controller.admin.apitest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "接口测试 - 有序键值，允许重复名称")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiTestKeyValueVO {

    @NotBlank(message = "名称不能为空")
    @Size(max = 256, message = "名称不能超过 256 个字符")
    private String name;

    @NotNull(message = "值不能为 null")
    @Size(max = 16_384, message = "值不能超过 16384 个字符")
    private String value;
}
