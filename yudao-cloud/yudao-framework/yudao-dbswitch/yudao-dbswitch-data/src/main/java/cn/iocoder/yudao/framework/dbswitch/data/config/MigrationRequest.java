package cn.iocoder.yudao.framework.dbswitch.data.config;


import lombok.Data;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 每次调用的输入；迁移服务在执行前保存自己的快照。
 *
 * @author jrl
 */
@Data
public class MigrationRequest {

    private List<SourceDataSourceProperties> source = new ArrayList<>();

    private TargetDataSourceProperties target = new TargetDataSourceProperties();

    /** 默认为空：跨产品的 LOSSY 列需要针对确切表范围确认预览结果。 */
    private List<cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement>
            typeMappingAcknowledgements = List.of();

    /**
     * 返回快照。
     */
    public MigrationRequest snapshot() {
        var result = new MigrationRequest();
        result.source = java.util.Objects.requireNonNull(source, "source").stream()
                .map(SourceDataSourceProperties::copy).toList();
        result.target = java.util.Objects.requireNonNull(target, "target").copy();
        result.typeMappingAcknowledgements = List.copyOf(typeMappingAcknowledgements);
        cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement
                .requireUnique(result.typeMappingAcknowledgements);
        return result;
    }
}
