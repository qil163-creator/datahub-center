package cn.iocoder.yudao.module.dataforge.framework.execution.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 实例本地的临时预览，不提供分布式归属管理或重启恢复能力。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "yudao.dataforge.etl-preview")
public class EtlPreviewProperties {

    @Min(1)
    @Max(4)
    private int concurrency = 2;

    @Min(1)
    @Max(8)
    private int maxSessions = 8;

    @Min(10)
    @Max(600)
    private int retentionSeconds = 120;

    @Min(10)
    @Max(120)
    private int executionSeconds = 45;

    @Min(1)
    @Max(30)
    private int cleanupGraceSeconds = 5;
}
