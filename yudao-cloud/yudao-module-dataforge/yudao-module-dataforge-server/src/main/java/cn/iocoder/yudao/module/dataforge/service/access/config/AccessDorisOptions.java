package cn.iocoder.yudao.module.dataforge.service.access.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 入中台库的建表选项；只持久化用户规则，物理 DDL 由 DB Switch 生成。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessDorisOptions(
        Partition partition,
        String distributionType,
        List<String> distributionColumns,
        String bucketMode,
        Integer bucketCount,
        Integer replicationNum) {

    public record Partition(String kind, List<String> columns, List<PartitionEntry> partitions) {

    }

    @Schema(description = "RANGE 的 values 依次为下界、上界元组；LIST 为各个取值元组，所有值以字符串传输")
    public record PartitionEntry(String name, List<List<String>> values) {

    }
}
