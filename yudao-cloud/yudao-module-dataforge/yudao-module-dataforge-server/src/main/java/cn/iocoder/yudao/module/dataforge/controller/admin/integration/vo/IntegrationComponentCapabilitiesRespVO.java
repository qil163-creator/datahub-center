package cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "当前集成引擎的组件执行能力，不替代具体流程检查或运行验收")
public record IntegrationComponentCapabilitiesRespVO(String engine, String mode, List<Component> components) {

    public IntegrationComponentCapabilitiesRespVO {
        components = List.copyOf(components);
    }

    @Schema(description = "组件执行支持及参数限制")
    public record Component(String componentCode, Status status, String reason) { }

    public enum Status { SUPPORTED, PARTIAL, UNAVAILABLE }
}
