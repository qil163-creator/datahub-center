package cn.iocoder.yudao.module.governance.dal.dataobject.rulever;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 脱敏规则版本 DO；PUBLISHED 后配置不可变，修改参数需发布新版本
 *
 * @author Li
 */
@TableName(value = "dh_governance_safety_rule_ver", autoResultMap = true)
@KeySequence("dh_safety_rule_ver_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyRuleVerDO extends BaseDO {

    /** 版本状态：草稿（可编辑） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 版本状态：已发布（不可变） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 版本状态：已废弃 */
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    /**
     * 规则版本编号
     */
    @TableId
    private Long id;

    /**
     * 脱敏规则编号（关联 dh_governance_safety_rule.id）
     */
    private Long ruleId;

    /**
     * 规则版本号，从1开始递增
     */
    private Integer versionNo;

    /**
     * config_json 结构版本，配置结构升级时递增
     */
    private Integer schemaVersion;

    /**
     * 脱敏参数，JSON格式
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String configJson;

    /**
     * 外部密钥引用编号（关联 dh_governance_safety_key_ref.id），仅 ENCRYPT/加盐 HASH 使用
     */
    private Long keyRefId;

    /**
     * 规则配置 SHA-256 摘要（hex），防篡改校验
     */
    private String configChecksum;

    /**
     * 状态：DRAFT草稿、PUBLISHED已发布、DEPRECATED已废弃
     */
    private String status;

    /**
     * 版本发布人
     */
    private String publishedBy;

    /**
     * 版本发布时间
     */
    private LocalDateTime publishedTime;

}
