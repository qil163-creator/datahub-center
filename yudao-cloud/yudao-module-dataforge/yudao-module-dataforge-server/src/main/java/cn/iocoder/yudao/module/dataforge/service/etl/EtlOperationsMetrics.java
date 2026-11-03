package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlOperationsSnapshot;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlOperationsMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/** 定时从持久状态采样；监控抓取只读内存。集群实例使用 max 聚合。 */
@Component
@Slf4j
public class EtlOperationsMetrics {

    private record Sample(Map<String, EtlOperationsSnapshot> values, long at, boolean available) {

    }

    private static final List<String> KINDS = List.of("commit", "quality", "evidence", "recheck");

    private final EtlOperationsMapper mapper;

    private final MeterRegistry registry;

    private volatile Sample sample = new Sample(Map.of(), 0, false);

    /**
     * 创建ETL操作指标实例。
     *
     * @param mapper 数据访问
     * @param registry 注册表
     */
    public EtlOperationsMetrics(EtlOperationsMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
        for (String kind : KINDS) {
            Gauge.builder("dataforge.etl.delivery.pending", this, m -> m.value(kind, 0)).tag("kind", kind)
                    .register(registry);
            Gauge.builder("dataforge.etl.delivery.failed", this, m -> m.value(kind, 1)).tag("kind", kind)
                    .register(registry);
            Gauge.builder("dataforge.etl.delivery.oldest.age", this, m -> m.value(kind, 2)).tag("kind", kind)
                    .baseUnit("seconds").register(registry);
        }
        Gauge.builder("dataforge.etl.delivery.metrics.available", this, m -> m.sample.available ? 1 : 0)
                .register(registry);
        Gauge.builder("dataforge.etl.delivery.metrics.age", this, m -> m.sample.at == 0 ? Double.NaN
                        : Math.max(0, (registry.config().clock().monotonicTime() - m.sample.at) / 1e9)).baseUnit("seconds")
                .register(registry);
    }

    /**
     * 刷新ETL操作指标。
     */
    public void refresh() {
        try {
            Map<String, EtlOperationsSnapshot> values = new HashMap<>();
            for (var row : mapper.snapshot()) {
                if (KINDS.contains(row.getKind())) {
                    values.put(row.getKind(), row);
                }
            }
            sample = new Sample(Map.copyOf(values), registry.config().clock().monotonicTime(), true);
        } catch (RuntimeException failure) {
            var old = sample;
            sample = new Sample(old.values, old.at, false);
            log.warn("ETL 持久状态指标采样失败 errorType={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 提取ETL操作指标字段值。
     *
     * @param kind 种类
     * @param field 字段
     * @return ETL操作指标
     */
    private double value(String kind, int field) {
        var current = sample;
        if (current.at == 0) {
            return Double.NaN;
        }
        var row = current.values.get(kind);
        if (row == null) {
            return 0;
        }
        return switch (field) {
            case 0 -> row.getPending();
            case 1 -> row.getFailed();
            default -> row.getOldestAge();
        };
    }
}
