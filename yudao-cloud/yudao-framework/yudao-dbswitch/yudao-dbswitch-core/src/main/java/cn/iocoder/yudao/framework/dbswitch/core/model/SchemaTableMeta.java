package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.List;

/**
 * 表元数据模型,在表描述基础上扩展主键列表、建表 SQL、字段描述列表与索引列表,用于返回表结构的完整详情。
 */
public class SchemaTableMeta extends TableDescription {

    private List<String> primaryKeys;

    private String createSql;

    private List<ColumnDescription> columns;

    /** 视图为空列表；表的索引包含主键背后的索引（移植自 dbswitch 2.0.2）。 */
    private List<IndexDescription> indexes;

    /**
     * 返回索引集合。
     */
    public List<IndexDescription> getIndexes() {
        return indexes;
    }

    /**
     * 设置索引集合。
     */
    public void setIndexes(List<IndexDescription> indexes) {
        this.indexes = indexes;
    }

    /**
     * 返回主键集合。
     */
    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    /**
     * 设置主键集合。
     */
    public void setPrimaryKeys(List<String> primaryKeys) {
        this.primaryKeys = primaryKeys;
    }

    /**
     * 返回创建SQL。
     */
    public String getCreateSql() {
        return createSql;
    }

    /**
     * 设置创建SQL。
     */
    public void setCreateSql(String createSql) {
        this.createSql = createSql;
    }

    /**
     * 返回列集合。
     */
    public List<ColumnDescription> getColumns() {
        return columns;
    }

    /**
     * 设置列集合。
     */
    public void setColumns(List<ColumnDescription> columns) {
        this.columns = columns;
    }
}
