package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.util.List;

/**
 * 行级值转换钩子（对齐 dbswitch 2.0.2 的 RecordTransformProvider 设计的单接口收敛版）
 * <p>
 * 在迁移链路中对"从源端读出的每一行数据"做转换（典型用途：数据脱敏、值映射、字典转换），
 * 转换发生在写入目标端/参与变化量比对之前，因此比对结果与最终落库数据一致。
 * <p>
 * 生效位置：
 * <ul>
 * <li>全量覆盖同步：每行组装完成后（此时字段布局为写入目标端的字段顺序）；</li>
 * <li>主键比对同步：源端（新数据）每行读出后，比对与写入使用转换后的值；</li>
 * <li>增量字段同步：源端每行读出后（主键存在性判断使用转换后的值）。</li>
 * </ul>
 * 使用方式：通过 {@code MigrationService} 的三参构造函数传入；返回 null 视为不转换。
 * 实现必须是线程安全的（多表并发迁移共用同一实例）
 *
 * @author yudao
 */
@FunctionalInterface
public interface RecordTransformer {

    /**
     * 转换一行数据
     *
     * @param schemaName 源端 schema 名
     * @param tableName  源端表名（SQL 抽取模式下为查询 SQL）
     * @param fieldNames 该行的字段名列表（与 record 下标一一对应）
     * @param record     行数据（可原地修改后返回，也可返回新数组）
     * @return 转换后的行数据；返回 null 视为不做转换（使用原行）
     */
    Object[] transform(String schemaName, String tableName, List<String> fieldNames, Object[] record);
}
