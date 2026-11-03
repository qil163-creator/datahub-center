package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据建模-模型物化发布日志
 */
@TableName("dh_modeling_model_materialized_log")
@KeySequence("dh_modeling_model_materialized_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelMaterializedLogDO extends BaseDO {

    @TableId
    private Long id;

    private Long materializedId;

    private Long modelId;

    /** 本条日志对应的持久部署尝试；历史未知不推断关联。 */
    private Long attemptId;

    /** 发布时展示快照，不从后续覆盖的物化记录回填。 */
    private String modelName;

    private String modelAlias;

    private Long datasourceId;

    private String datasourceName;

    private String datasourceType;

    private String description;

    private String publishMode;

    private String status;

    private String ddlSql;

    private String message;

    private String operator;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
