package cn.iocoder.yudao.module.infra.api.file.dto;

import lombok.Data;

/** 文件回执只暴露不透明编号与指纹；不包含访问地址、配置或对象路径。 */
@Data
public class ManagedFileRespDTO {
    private Long id;
    private Long size;
    private String sha256;
    private String state;
}
