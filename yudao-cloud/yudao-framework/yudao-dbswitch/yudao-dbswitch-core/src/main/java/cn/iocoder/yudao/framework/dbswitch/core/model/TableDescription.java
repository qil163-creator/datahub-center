package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.DBTableType;

/**
 * 数据库表描述符信息定义(Table Description)
 *
 * @author jrl
 */
public class TableDescription {

    private String tableName;

    private String schemaName;

    /** 目录读取器观测到的可选原生对象 ID，不等于授权或持久固定信息。 */
    private String catalogObjectId;

    private String remarks;

    private DBTableType tableType;

    private String sql;

    /**
     * 返回表名。
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 设置表名。
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 返回模式名。
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * 设置模式名。
     */
    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    /**
     * 返回系统目录对象编号。
     */
    public String getCatalogObjectId() {
        return catalogObjectId;
    }

    /**
     * 设置系统目录对象编号。
     */
    public void setCatalogObjectId(String catalogObjectId) {
        this.catalogObjectId = catalogObjectId;
    }

    /**
     * 返回备注。
     */
    public String getRemarks() {
        return this.remarks;
    }

    /**
     * 设置备注。
     */
    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    /**
     * 返回表类型。
     */
    public String getTableType() {
        return tableType.name();
    }

    /**
     * 设置表类型。
     */
    public void setTableType(String tableType) {
        this.tableType = DBTableType.valueOf(tableType.toUpperCase());
    }

    /**
     * 判断当前对象是否为视图。
     */
    public boolean isViewTable() {
        return DBTableType.VIEW == tableType;
    }

    /**
     * 返回SQL 文本。
     */
    public String getSql() {
        return sql;
    }

    /**
     * 设置SQL 文本。
     */
    public void setSql(String sql) {
        this.sql = sql;
    }
}
