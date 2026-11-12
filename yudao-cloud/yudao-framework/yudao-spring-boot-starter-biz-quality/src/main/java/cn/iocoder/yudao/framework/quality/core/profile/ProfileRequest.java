package cn.iocoder.yudao.framework.quality.core.profile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次探查 SQL 生成请求：目标产品、物理表定位、待探查列、抽样行数与 Top-N 数量。
 *
 * @param product    目标数据库产品
 * @param schemaName 物理 schema（Hive/MySQL 为库名）；为空时只引用表名，由连接默认 schema 解析
 * @param tableName  物理表名
 * @param columns    待探查列（单次最多 {@link #MAX_COLUMNS}，不得重名）
 * @param sampleRows 抽样行数上限，1 ~ {@link #MAX_SAMPLE_ROWS}
 * @param topN       每列 Top 值数量，1 ~ {@link #MAX_TOP_N}
 * @author Li
 */
public record ProfileRequest(ProductTypeEnum product, String schemaName, String tableName,
                             List<ProfileColumn> columns, int sampleRows, int topN) {

    /** 默认抽样行数 */
    public static final int DEFAULT_SAMPLE_ROWS = 100_000;
    /** 抽样上限，与 DB Switch limitGeneratedSelect 的有界行数一致 */
    public static final int MAX_SAMPLE_ROWS = 1_000_000;
    /** 默认 Top-N */
    public static final int DEFAULT_TOP_N = 10;
    /** Top-N 上限 */
    public static final int MAX_TOP_N = 100;
    /**
     * 单条聚合 SQL 最多覆盖的列数：每列最多 7 个表达式，100 列约 701 个，低于 Oracle 1000 个选择项上限。
     * 更多列由调用方分批。
     */
    public static final int MAX_COLUMNS = 100;

    /**
     * 校验请求边界；产品支持性与标识符合法性由生成器在引用时校验。
     */
    public ProfileRequest {
        Objects.requireNonNull(product, "product");
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Profile table name is required");
        }
        if (columns == null || columns.isEmpty() || columns.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Profile columns must contain 1.." + MAX_COLUMNS + " entries");
        }
        Set<String> names = new HashSet<>();
        for (ProfileColumn column : columns) {
            if (column == null || !names.add(column.name())) {
                throw new IllegalArgumentException("Profile columns must be distinct and non-null");
            }
        }
        if (sampleRows < 1 || sampleRows > MAX_SAMPLE_ROWS) {
            throw new IllegalArgumentException("Profile sample rows must be within 1.." + MAX_SAMPLE_ROWS);
        }
        if (topN < 1 || topN > MAX_TOP_N) {
            throw new IllegalArgumentException("Profile top N must be within 1.." + MAX_TOP_N);
        }
        columns = List.copyOf(columns);
        schemaName = schemaName == null || schemaName.isEmpty() ? null : schemaName;
    }

}
