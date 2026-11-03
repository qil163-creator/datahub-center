package cn.iocoder.yudao.module.dataforge.api.migration.dto;

/**
 * 无连接信息的实际表级流水线事实。所有字段均可空以兼容旧 JSON，缺失不能推导为 0/false。
 * 写入器确认行数不是数据库原生提交位置，更不证明目标读取快照可见或字段值相等。
 */
public record SyncTableTransferDTO(Integer schemaVersion, String operation, Long readRows, Long acknowledgedRows,
                                   Integer sourceColumns, Integer copiedColumns, Long substitutedNullValues,
                                   Boolean transformed, Boolean generatedColumns, Boolean customScripts,
                                   SyncCommitReceiptsDTO commits) {

    /**
     * 创建同步表传输实例。
     *
     * @param schemaVersion 结构版本
     * @param operation 操作
     * @param readRows 读取行
     * @param acknowledgedRows 已确认行
     * @param sourceColumns 来源列集合
     * @param copiedColumns 已复制列集合
     * @param substitutedNullValues 已替换空值值
     * @param transformed 已转换
     * @param generatedColumns 生成列集合
     * @param customScripts 自定义脚本
     */
    public SyncTableTransferDTO(Integer schemaVersion, String operation, Long readRows, Long acknowledgedRows,
            Integer sourceColumns, Integer copiedColumns, Long substitutedNullValues,
            Boolean transformed, Boolean generatedColumns, Boolean customScripts) {
        this(schemaVersion, operation, readRows, acknowledgedRows, sourceColumns, copiedColumns, substitutedNullValues,
                transformed, generatedColumns, customScripts, null);
    }
}
