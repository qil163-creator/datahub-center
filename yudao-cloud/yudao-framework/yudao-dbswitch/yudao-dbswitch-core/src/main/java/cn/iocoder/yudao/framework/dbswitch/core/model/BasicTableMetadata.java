package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.List;

/**
 * 通过产品现有元数据读取获得的基本列描述和主键顺序。
 * 列表和可变列描述均进行防御性复制；本对象保留方言
 * 返回的事实，不将列标记为键，也不补齐完整结构信息。它
 * 不足以重建完整 DDL。共享连接不代表结构快照具有事务一致性，
 * 本结果也不承诺跨产品统一的总超时。
 */
public record BasicTableMetadata(List<ColumnDescription> columns, List<String> primaryKeys) {

    /**
     * 初始化 {@code BasicTableMetadata}。
     */
    public BasicTableMetadata {
        columns = columns.stream().map(ColumnDescription::copy).toList();
        primaryKeys = List.copyOf(primaryKeys);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    @Override
    public List<ColumnDescription> columns() {
        return columns.stream().map(ColumnDescription::copy).toList();
    }
}
