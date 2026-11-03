package cn.iocoder.yudao.framework.dbswitch.data.entity;

import lombok.Data;
import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.DBTableType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
public class SourceDataSourceProperties implements Cloneable {

    /** 显式源端契约：更新必须推进水位；不支持旧水位的迟到写入和物理删除。 */
    private String cursorWatermarkContract;

    /** 存在旧版字符串水位历史时，显式重新启动类型化协议。 */
    private Boolean cursorBootstrapAllowed = Boolean.FALSE;

    /** 保留 SQL Server 自增标识的外部契约：源端 DML、DDL 和自增标识分配
     * 在整个传输期间必须停止；此声明不会获取外部锁。 */
    private Boolean identitySourceQuiesced = Boolean.FALSE;


    private Integer poolSize = 32;

    private ProductTypeEnum sourceProductType;

    @lombok.ToString.Exclude
    private String url;

    private String driverClassName;

    /**
     * 驱动 jar 路径（文件或目录，目录含子目录递归；移植自 dbswitch 2.0.2）：
     * 配置后通过隔离 ClassLoader 动态加载驱动，避免与应用 classpath 中的驱动冲突；
     * 为空时沿用应用 classpath 中的驱动（历史行为）
     */
    private String driverPath;

    /** 部署配置标识，不是文件系统路径或上传的 JAR。 */
    private String driverProfileId;

    @lombok.ToString.Exclude
    private String username;

    @lombok.ToString.Exclude
    private String password;

    private Long connectionTimeout = TimeUnit.SECONDS.toMillis(120);

    private Long maxLifeTime = TimeUnit.MINUTES.toMillis(120);

    private Integer sourceType = 1;

    private String sourceSql;

    private Integer fetchSize = 5000;

    private DBTableType tableType;

    private String sourceSchema = "";

    private Integer includeOrExclude;

    private String sourceIncludes = "";

    private String sourceExcludes = "";

    /** 可选的字面值选择；null 保留旧版 CSV 或模式，不得裁剪或拆分这些名称。 */
    private List<String> sourceTableNames;

    /** PostgreSQL 读取父表时默认包含子表；ONLY 仅限全量读取。 */
    private cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode tableReadMode =
            cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode.INCLUDE_DESCENDANTS;

    private String mapperType = MapperType.ALL.name();

    //批量映射（适用于所有表的映射规则一样）
    private List<String> sourcePrimaryKeys;

    private String increaseColumnName;

    private ColumnDescription increaseColumn;

    /**
     * 增量字段的开始/结束水位值（ALL 映射模式下的全局配置；
     * 未配置时增量同步会自动以目标表该字段的 MAX 值作为起始水位）
     */
    private String increaseStartVal;

    private String increaseEndVal;

    //时间序列字段，适用于时序数据库（批量模式，所有的表都用这个字段）
    private String timestampColumn;

    private List<PatternMapper> regexTableMapper;

    private List<PatternMapper> regexColumnMapper;

    //单个表对表映射配置（（适用于对每个表的映射的规则进行个性化定制））
    private Map<String, MapperConfig> configMap;

    /**
     * 任务开始前在源库执行的 SQL 脚本（多条以分号分隔），移植自 dbswitch 2.0.2
     */
    private String beforeSqlScripts;

    /**
     * 任务结束后在源库执行的 SQL 脚本（多条以分号分隔），移植自 dbswitch 2.0.2
     */
    private String afterSqlScripts;

    /** 异步运行开始前，保存调用方管理的集合和元数据。 */
    public SourceDataSourceProperties copy() {
        try {
            var result = (SourceDataSourceProperties) super.clone();
            result.sourceTableNames = cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.exactNames(
                    this);
            result.sourcePrimaryKeys = sourcePrimaryKeys == null ? null : List.copyOf(sourcePrimaryKeys);
            result.regexTableMapper = MapperConfig.copyPatterns(regexTableMapper);
            result.regexColumnMapper = MapperConfig.copyPatterns(regexColumnMapper);
            result.increaseColumn = increaseColumn == null ? null : increaseColumn.copy();
            if (configMap != null) {
                var entries = new java.util.LinkedHashMap<String, MapperConfig>();
                configMap.forEach((key, value) -> entries.put(key, value.copy()));
                result.configMap = Map.copyOf(entries);
            }
            return result;
        } catch (CloneNotSupportedException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
