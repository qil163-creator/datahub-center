package cn.iocoder.yudao.module.infra.api.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 受管文件上传；调用方只提供稳定操作键，不提供存储路径。 */
@Data
public class ManagedFileCreateReqDTO {
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,128}")
    private String operationKey;
    @NotBlank @Size(max = 255)
    private String name;
    @NotNull @Size(min = 1, max = 20 * 1024 * 1024)
    private byte[] content;
}
