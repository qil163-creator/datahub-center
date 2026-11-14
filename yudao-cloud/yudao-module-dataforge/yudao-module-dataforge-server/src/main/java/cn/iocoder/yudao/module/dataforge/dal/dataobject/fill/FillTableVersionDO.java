package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-模板版本 DO（发布后不可变，任务永远绑定版本）
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_table_version", autoResultMap = true)
@KeySequence("dh_dataforge_fill_table_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTableVersionDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 模板主表编号（dh_dataforge_fill_table.id）
     */
    private Long tableId;

    /**
     * 版本号，自 1 递增
     */
    private Integer versionNo;

    /**
     * 模板名称快照
     */
    private String name;

    /**
     * 状态：1-已发布 2-已停用（停用仅禁止新任务引用，不影响进行中任务）
     */
    private Integer status;

    /**
     * 字段结构 JSON：fieldCode/名称/类型/精度/输入方式/必填/字典/targetColumnName
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String fieldSchema;

    /**
     * 单 Sheet 布局快照 JSON：列宽/样式/冻结/可编辑区域
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String layoutSnapshot;

    /**
     * 公式定义 JSON：fieldCode 表达式/依赖/精度舍入规则/函数白名单版本
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String formulaSchema;

    /**
     * 校验规则 JSON：字段/行内/跨行/业务规则
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String validationSchema;

    /**
     * 目标映射 JSON：目标数据源/表/字段映射/系统来源字段
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String targetMapping;

    /**
     * 合并策略：APPEND/UPSERT/REPLACE_SCOPE，见 FillMergeStrategyEnum
     */
    private String mergeStrategy;

    /**
     * UPSERT 业务键 fieldCode 列表（英文逗号分隔）
     */
    private String businessKeyFields;

    /**
     * 发布内容摘要（SHA-256）
     */
    private String contentHash;

    /**
     * 前端 Univer SDK 版本
     */
    private String univerSdkVersion;

    /**
     * 适配器协议版本
     */
    private String adapterProtocolVersion;

    /**
     * 布局快照结构版本
     */
    private String snapshotSchemaVersion;

    /**
     * 发布人编号
     */
    private Long releaseUserId;

    /**
     * 发布时间
     */
    private LocalDateTime releaseTime;

    /**
     * 发布时固化的多级汇总规则 JSON：{"mode","groupFields","funcs"}
     */
    private String rollupSchema;

}
