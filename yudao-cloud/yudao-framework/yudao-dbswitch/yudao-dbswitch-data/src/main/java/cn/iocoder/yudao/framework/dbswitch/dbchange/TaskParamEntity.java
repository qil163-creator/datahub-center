package cn.iocoder.yudao.framework.dbswitch.dbchange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 任务参数实体类定义
 *
 * @author jrl
 */
@Data
@Builder
@AllArgsConstructor
public class TaskParamEntity {


    private ProductTypeEnum oldProductType;

    /**
     * 老表的数据源
     */
    @NonNull
    private DataSource oldDataSource;

    /**
     * 老表的schema名
     */
    @NonNull
    private String oldSchemaName;

    /**
     * 老表的table名
     */
    @NonNull
    private String oldTableName;

    private ProductTypeEnum newProductType;

    /**
     * 新表的数据源
     */
    @NonNull
    private DataSource newDataSource;

    /**
     * 新表的schema名
     */
    @NonNull
    private String newSchemaName;

    private Integer sourceType;

    private String sourceSql;

    private List<String> sourcePrimaryKeys;

    /**
     * 新表的table名
     */
    @NonNull
    private String newTableName;

    /**
     * 字段列
     */
    private List<String> fieldColumns;

    /**
     * 字段名映射关系
     */
    @NonNull
    @Builder.Default
    private Map<String, String> columnsMap = Collections.EMPTY_MAP;

    /**
     * 行级值转换钩子（可选）：对源端（新数据）每行读出后应用，null 表示不转换
     */
    private RecordTransformer transformer;
}
