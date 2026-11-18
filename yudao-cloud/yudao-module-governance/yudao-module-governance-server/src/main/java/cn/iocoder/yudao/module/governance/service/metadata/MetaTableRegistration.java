package cn.iocoder.yudao.module.governance.service.metadata;

import lombok.Data;

import java.util.List;

/** 建模与主数据发布回写所需的表元数据。 */
@Data
public class MetaTableRegistration {

    private Long datasourceId;

    private String sourceSystemName;

    private String dbName;

    private String tableName;

    private String tableComment;

    private List<Column> columns;

    @Data
    public static class Column {

        private String columnName;

        private String columnComment;

        private String dataType;

        private Long dataLength;

        private Integer dataPrecision;

        private Integer dataScale;

        private Integer nullableFlag;

        private Integer pkFlag;

    }

}
