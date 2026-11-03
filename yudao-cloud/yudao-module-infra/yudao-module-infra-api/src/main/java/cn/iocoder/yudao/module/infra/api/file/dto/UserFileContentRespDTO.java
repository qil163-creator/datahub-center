package cn.iocoder.yudao.module.infra.api.file.dto;

import lombok.Data;

/** 仅供经过服务认证的调用方读取用户自己的有界上传，不暴露存储配置或对象地址。 */
@Data
public class UserFileContentRespDTO {
    private String name;
    private String type;
    private Long size;
    private byte[] content;
}
