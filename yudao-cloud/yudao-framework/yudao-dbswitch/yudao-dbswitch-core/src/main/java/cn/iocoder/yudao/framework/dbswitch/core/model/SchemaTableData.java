package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.Data;

import java.util.List;

/**
 * 数据库表的数据
 *
 * @author jrl
 */
@Data
public class SchemaTableData {

    private String schemaName;

    private String tableName;

    private List<String> columns;

    private List<List<Object>> rows;

    private String sql;

}
