package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据建模-模型物化发布记录
 */
@TableName(value = "dh_modeling_model_materialized", autoResultMap = true)
@KeySequence("dh_modeling_model_materialized_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelMaterializedDO extends BaseDO {

    public static final String MODE_REBUILD = "REBUILD";

    public static final String MODE_INCREMENT = "INCREMENT";

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAIL = "FAIL";

    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 列表态：逻辑模型尚无物化记录 */
    public static final String STATUS_UNPUBLISHED = "UNPUBLISHED";

    @TableId
    private Long id;

    private Long modelId;

    private Long datasourceId;

    /** 本次部署准备时的设计修订标记，未知历史记录为空。 */
    private Long designRevision;

    /** 部署产物的字段输入，不能由后续当前设计覆盖。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler.class)
    private String fieldsJson;

    private Long attemptId;

    private String datasourceType;

    private String datasourceName;

    /** 表英文名 / 模型编码 */
    private String modelName;

    /** 表中文名 / 模型名称 */
    private String modelAlias;

    private String schemaName;

    private String tableName;

    private String publishMode;

    private String status;

    private String ddlSql;

    private String message;

    private String description;

    private String remark;
}
