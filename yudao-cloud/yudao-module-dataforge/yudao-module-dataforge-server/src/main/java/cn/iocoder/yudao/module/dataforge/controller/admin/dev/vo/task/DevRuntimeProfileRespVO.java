package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "当前平台可选择的受管原生运行环境，不包含地址、凭据或内部资源路径")
public class DevRuntimeProfileRespVO {

    private String code;

    private String name;

    private String engine;

    private String engineVersion;

    private String deployMode;

    private String queue;

    private List<String> allowedTaskTypes;

    private List<ResourceOption> artifacts;

    private List<ResourceOption> dependencyResources;

    @Data
    public static class ResourceOption {

        private Long fileId;

        private String name;

        private String sha256;

        private List<String> programTypes;

        private String entryPoint;
    }
}
