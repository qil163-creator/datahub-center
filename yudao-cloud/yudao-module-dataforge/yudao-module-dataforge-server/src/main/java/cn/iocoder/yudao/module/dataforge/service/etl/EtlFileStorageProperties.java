package cn.iocoder.yudao.module.dataforge.service.etl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 平台运维配置的逻辑根。服务端技术白名单仍须显式覆盖这些目录。 */
@Data
@Component
@ConfigurationProperties(prefix = "yudao.dataforge.etl-files")
public class EtlFileStorageProperties {

    private Map<String, String> storageRoots = new LinkedHashMap<>();
}
