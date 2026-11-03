package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-目标表写入登记 DO（计划发布时校验同表写入范围冲突，冲突则拒绝发布）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_target_scope_registry")
@KeySequence("dh_dataforge_fill_target_scope_registry_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTargetScopeRegistryDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 目标数据源编号
     */
    private Long targetDatabaseId;

    /**
     * 目标物理表名
     */
    private String targetTableName;

    /**
     * 采集计划编号
     */
    private Long planId;

    /**
     * 模板版本编号
     */
    private Long tableVersionId;

    /**
     * 合并策略：APPEND/UPSERT/REPLACE_SCOPE
     */
    private String mergeStrategy;

    /**
     * 写入范围描述（如 orgId+periodKey），用于冲突判定与人工审查
     */
    private String scopeDesc;

    /**
     * 状态：1-占用中 0-已释放（计划停用后释放）
     */
    private Integer status;

}
