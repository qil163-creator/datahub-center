package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessCheckpointDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessCheckpointMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessIncreaseLogMapper;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** 业务层只拥有任务租约、任务归属和检查点 CAS；源端排序与搬数由 DB Switch 执行。 */
@Component
@RequiredArgsConstructor
public class AccessCursorCheckpointStore {

    private final DataAccessCheckpointMapper checkpoints;

    private final DataAccessIncreaseLogMapper legacy;

    private final AccessRunCoordinator coordinator;

    private final DbSwitchRuntime runtime;

    private final ConfiguredSourceGenerations generations;

    /**
     * 准备访问游标检查点所需的配置和状态。
     *
     * @param access 访问
     * @param run 运行
     * @param lease 租约
     * @param source 来源数据
     * @param target 目标数据
     * @param sourceConnection 来源连接
     * @param targetConnection 目标连接
     * @return 处理后的键值映射
     */
    public Map<String, CursorRunBinding> prepare(DataAccessDO access, DataAccessTaskDO run, RunLease lease,
            SourceDataSourceProperties source, TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection) {
        if (!Boolean.TRUE.equals(target.getChangeDataSync())
                || !ChangeDataSyncType.INCREASE_COLUMN.name().equals(target.getChangeDataSyncType())) {
            return Map.of();
        }
        if (!Integer.valueOf(1).equals(source.getSourceType())
                || !"MONOTONIC_WATERMARK_V1".equals(source.getCursorWatermarkContract())
                || Boolean.TRUE.equals(target.getTargetDrop()) || Boolean.TRUE.equals(target.getTargetTruncateData())
                || Boolean.TRUE.equals(target.getOnlyCreate()) || text(source.getBeforeSqlScripts()) || text(
                source.getAfterSqlScripts())
                || text(target.getBeforeSqlScripts()) || text(target.getAfterSqlScripts())
                || text(source.getIncreaseStartVal()) || text(source.getIncreaseEndVal())
                || source.getSourceSchema() == null || source.getSourceSchema().isBlank()) {
            throw new IllegalArgumentException("类型化增量要求已实现游标策略的表、单调水位声明及无脚本、无清表的配置");
        }
        var selected = AccessExactTableSelection.selectedNames(source);
        var sourceProduct = source.getSourceProductType();
        var targetProduct = target.getTargetProductType();
        runtime.products().requireCursorPair(sourceProduct, targetProduct);
        var sourceDriver = runtime.drivers().find(sourceProduct)
                .orElseThrow(() -> new IllegalArgumentException("类型化增量必须固定源驱动摘要"));
        var targetDriver = runtime.drivers().find(targetProduct)
                .orElseThrow(() -> new IllegalArgumentException("类型化增量必须固定目标驱动摘要"));
        sourceDriver.verifyUnchanged();
        targetDriver.verifyUnchanged();
        var pin = generations.find(sourceProduct, source.getUrl(), source.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("源数据库未配置部署代次，禁止推断恢复位置"));
        var targetPin = Set.of(ProductTypeEnum.SQLSERVER2000, ProductTypeEnum.GBASE8A).contains(targetProduct)
                ? generations.find(targetProduct, target.getUrl(), target.getUsername())
                .orElseThrow(
                        () -> new IllegalArgumentException("目标数据库未配置部署代次，禁止恢复可能已清空或还原的目标"))
                : null;
        if (!text(sourceConnection.getConfigurationRevision()) || !text(targetConnection.getConfigurationRevision())
                || run.getConfigJson() == null) {
            throw new IllegalArgumentException("增量绑定信息不完整");
        }
        var metadata = runtime.jdbcMetadata(sourceProduct);
        var names = new LinkedHashSet<>(
                metadata.queryTableList(source.getUrl(), source.getUsername(), source.getPassword(),
                                source.getSourceSchema())
                        .stream().filter(table -> !table.isViewTable()).map(table -> table.getTableName()).toList());
        if (Integer.valueOf(1).equals(source.getIncludeOrExclude()) && !selected.isEmpty()) {
            if (!names.containsAll(selected)) {
                throw new IllegalArgumentException("增量表选择必须是当前存在的明确表名");
            }
            names.retainAll(selected);
        } else if (Integer.valueOf(0).equals(source.getIncludeOrExclude())) {
            names.removeAll(selected);
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("未选择可增量同步的表");
        }
        Map<String, CursorRunBinding> bindings = new LinkedHashMap<>();
        for (String table : names) {
            var mapping = source.getConfigMap() == null ? null : source.getConfigMap().get(table);
            String watermark = MapperType.SINGLE.name().equalsIgnoreCase(source.getMapperType())
                    ? mapping == null ? null : mapping.getIncreaseColumnName() : source.getIncreaseColumnName();
            if (!text(watermark) || mapping != null && (text(mapping.getIncreaseStartVal()) || text(
                    mapping.getIncreaseEndVal()))) {
                throw new IllegalArgumentException("每张增量表必须显式配置字段，不能沿用字符串水位");
            }
            var bindingIdentity = new ArrayList<Object>(
                    List.of("ACCESS_TYPED_CURSOR_V1", access.getId(), DigestUtil.sha256Hex(run.getConfigJson()),
                            sourceConnection.getDatabaseId(), targetConnection.getDatabaseId(),
                            sourceConnection.getConfigurationRevision(),
                            targetConnection.getConfigurationRevision(), sourceDriver.profileId(),
                            sourceDriver.fingerprint(),
                            targetDriver.profileId(), targetDriver.fingerprint(), pin.fingerprint(),
                            source.getSourceSchema(), table, watermark));
            // 保留此前已认证的 PostgreSQL 专用标识，使 V1 PostgreSQL 检查点能够恢复执行。
            // 每个新增数据库产品组合都需明确配置，不能因路由恰好相同而继承 PostgreSQL 检查点。
            if (sourceProduct != ProductTypeEnum.POSTGRESQL || targetProduct != ProductTypeEnum.POSTGRESQL) {
                bindingIdentity.set(0, "ACCESS_TYPED_CURSOR_V2");
                bindingIdentity.add(sourceProduct.name());
                bindingIdentity.add(targetProduct.name());
            }
            if (targetPin != null) {
                bindingIdentity.add("TARGET_DEPLOYED_GENERATION_V1");
                bindingIdentity.add(targetPin.fingerprint());
            }
            String fingerprint = DigestUtil.sha256Hex(JsonUtils.toJsonString(bindingIdentity));
            DataAccessCheckpointDO current = checkpoints.current(access.getId(), source.getSourceSchema(), table);
            if (current == null && !Boolean.TRUE.equals(source.getCursorBootstrapAllowed())
                    && legacy.selectNewest(access.getId(), source.getSourceSchema(), table, watermark) != null) {
                throw new IllegalArgumentException("存在旧字符串水位；必须显式重新引导类型化协议，不能静默转换");
            }
            CursorCheckpoint previous = current == null ? null : decode(current.getCheckpointJson());
            bindings.put(table, new CursorRunBinding(fingerprint, previous,
                    new LeaseCheckpointWriter(access.getId(), run.getId(), lease,
                            source.getSourceSchema(), table, watermark, fingerprint, current), pin, targetPin));
        }
        return Map.copyOf(bindings);
    }

    private final class LeaseCheckpointWriter implements CursorRunBinding.CheckpointStore {

        private final Long task, run;

        private final RunLease lease;

        private final String schema, table, watermark, fingerprint;

        private Long id;

        private long revision;

        /**
         * 创建租约检查点写入器实例。
         *
         * @param task 任务记录
         * @param run 运行
         * @param lease 租约
         * @param schema 结构
         * @param table 表
         * @param watermark 水位
         * @param fingerprint 指纹
         * @param current 当前
         */
        private LeaseCheckpointWriter(Long task, Long run, RunLease lease, String schema,
                String table, String watermark, String fingerprint, DataAccessCheckpointDO current) {
            this.task = task;
            this.run = run;
            this.lease = lease;
            this.schema = schema;
            this.table = table;
            this.watermark = watermark;
            this.fingerprint = fingerprint;
            id = current == null ? null : current.getId();
            revision = current == null ? 0 : current.getRevision();
        }

        /**
         * 比较并设置。
         *
         * @param value 待处理的值
         * @return 处理是否成功
         */
        @Override
        public synchronized boolean compareAndSet(CursorCheckpoint value) {
            if (!fingerprint.equals(value.bindingFingerprint())) {
                throw new IllegalArgumentException("增量检查点绑定发生变化");
            }
            String json = JsonUtils.toJsonString(value);
            if (json.length() > 262144) {
                throw new IllegalArgumentException("增量检查点超过大小上限");
            }
            Long[] inserted = new Long[1];
            boolean stored = coordinator.writeOwned(lease, () -> {
                if (id == null) {
                    var row = new DataAccessCheckpointDO();
                    row.setDataAccessId(task);
                    row.setSchemaName(schema);
                    row.setTableName(table);
                    row.setWatermarkColumn(watermark);
                    row.setOwnerRunId(run);
                    row.setRevision(0L);
                    row.setCheckpointJson(json);
                    checkpoints.insert(row);
                    inserted[0] = row.getId();
                } else if (checkpoints.compareAndSet(id, task, schema, table, revision, run, json) != 1) {
                    throw new IllegalStateException("增量检查点版本冲突，禁止旧执行覆盖");
                }
            });
            if (stored) {
                if (id == null) {
                    id = inserted[0];
                } else {
                    revision++;
                }
            }
            return stored;
        }
    }

    /**
     * 解码访问游标检查点。
     *
     * @param json JSON 内容
     * @return 游标检查点
     */
    static CursorCheckpoint decode(String json) {
        try {
            return JsonUtils.getObjectMapper().readValue(json, CursorCheckpoint.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("历史类型化检查点格式无效，需核查后重新引导");
        }
    }

    /**
     * 判断文本是否非空且包含有效字符。
     *
     * @param value 待处理的值
     * @return 处理是否成功
     */
    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
