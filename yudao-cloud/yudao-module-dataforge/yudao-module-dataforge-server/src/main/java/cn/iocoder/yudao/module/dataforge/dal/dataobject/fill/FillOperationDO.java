package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-草稿操作日志 DO（只追加，协同重放与强审计事实源）
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_operation", autoResultMap = true)
@KeySequence("dh_dataforge_fill_operation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillOperationDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 工作项内单调递增版本（协同拉取增量的游标）
     */
    private Long serverVersion;

    /**
     * 客户端操作号（网络重试去重）
     */
    private String clientOpId;

    /**
     * 客户端会话标识
     */
    private String clientSessionId;

    /**
     * 操作类型：SET_CELL/ADD_ROW/REMOVE_ROW/REORDER_ROW/IMPORT_CONFIRM/PREFILL，见 FillOperationTypeEnum
     */
    private String opType;

    /**
     * 目标行标识
     */
    private String rowId;

    /**
     * 目标字段码
     */
    private String fieldCode;

    /**
     * 旧值 JSON（敏感字段按制度脱敏/加密后存储）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String oldValue;

    /**
     * 新值 JSON（敏感字段按制度脱敏/加密后存储）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String newValue;

    /**
     * 操作人编号
     */
    private Long operatorUserId;

}
