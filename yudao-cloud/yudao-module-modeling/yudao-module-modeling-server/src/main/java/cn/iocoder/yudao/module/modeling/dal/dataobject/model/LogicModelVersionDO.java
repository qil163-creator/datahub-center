package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据建模-逻辑模型版本快照。每次发布在同一事务内生成，写入后只读，只用于查看与比对。
 */
@TableName(value = "dh_modeling_logic_model_version", autoResultMap = true)
@KeySequence("dh_modeling_logic_model_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicModelVersionDO extends BaseDO {

    @TableId
    private Long id;

    /** 逻辑模型编号 */
    private Long modelId;

    /** 模型内递增的版本号，从 1 开始 */
    private Integer versionNo;

    /** 发布完成时模型的修订号 */
    private Long modelRevision;

    /** 发布时的模型表头快照 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String modelSnapshot;

    /** 发布时的字段快照 JSON 数组 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String fieldsSnapshot;

    /** 发布时的表类型专属口径配置 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String typeConfig;

    /** 快照字段数 */
    private Integer fieldCount;

    /** 相对上一版本的变化摘要 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String changeSummary;

    /** 发布人用户编号 */
    private Long publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedTime;
}
