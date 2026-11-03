package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.physical;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Doris 物理建模扩展配置（物理模型 doris_ext JSON 结构）
 *
 * @author Li
 */
@Schema(description = "Doris 物理建模扩展配置")
@Data
public class DorisExtVO {

    /** 表模型：明细模型（Key 仅排序，不去重） */
    public static final String MODEL_DUPLICATE = "DUPLICATE";

    /** 表模型：主键模型（同 Key 覆盖/合并） */
    public static final String MODEL_UNIQUE = "UNIQUE";

    /** 表模型：聚合模型（同 Key 按聚合函数预聚合） */
    public static final String MODEL_AGGREGATE = "AGGREGATE";

    /** 分区模式 */
    public static final String PARTITION_NONE = "NONE";

    public static final String PARTITION_RANGE = "RANGE";

    public static final String PARTITION_LIST = "LIST";

    public static final String PARTITION_AUTO_RANGE = "AUTO_RANGE";

    public static final String PARTITION_AUTO_LIST = "AUTO_LIST";

    /** 分桶方式 */
    public static final String DISTRIBUTION_HASH = "HASH";

    public static final String DISTRIBUTION_RANDOM = "RANDOM";

    /** 桶数模式 */
    public static final String BUCKET_AUTO = "AUTO";

    public static final String BUCKET_FIXED = "FIXED";

    @Schema(description = "表模型：DUPLICATE/UNIQUE/AGGREGATE", example = "UNIQUE")
    private String tableModel;

    @Schema(description = "Key 列（按顺序，将自动排到字段最前）", example = "[\"id\"]")
    private List<String> keyColumns;

    @Schema(description = "分区模式：NONE/RANGE/LIST/AUTO_RANGE/AUTO_LIST", example = "NONE")
    private String partitionMode;

    @Schema(description = "分区列", example = "[\"create_date\"]")
    private List<String> partitionColumns;

    @Schema(description = "AUTO RANGE 分区函数表达式（如 date_trunc(`dt`,'day')，为空自动生成）")
    private String partitionExpr;

    @Schema(description = "分桶方式：HASH/RANDOM（RANDOM 仅明细模型）", example = "HASH")
    private String distributionType;

    @Schema(description = "HASH 分桶列", example = "[\"id\"]")
    private List<String> distributionColumns;

    @Schema(description = "桶数模式：AUTO/FIXED", example = "AUTO")
    private String bucketMode;

    @Schema(description = "固定桶数（bucketMode=FIXED 时生效）", example = "10")
    private Integer bucketCount;

    @Schema(description = "副本数", example = "1")
    private Integer replicationNum;

    @Schema(description = "主键模型启用 Merge-on-Write", example = "true")
    private Boolean enableMow;

    @Schema(description = "主键模型 Sequence 列（乱序更新控制，需为表中字段）", example = "update_time")
    private String sequenceColumn;

}
