package cn.iocoder.yudao.module.governance.dal.dataobject.security;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据安全-密钥引用。本表不存任何密钥材料：KEY_CENTER 引用的数据密钥由部署环境注入的主密钥按
 * externalKeyId 派生，KMS/VAULT 引用指向外部密钥服务。
 */
@TableName("dh_governance_safety_key_ref")
@KeySequence("dh_safety_key_ref_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SafetyKeyRefDO extends BaseDO {

    /** 本地主密钥派生。 */
    public static final String PROVIDER_KEY_CENTER = "KEY_CENTER";
    /** SM4 对称密钥。 */
    public static final String KEY_TYPE_SM4 = "SM4_SYMMETRIC";
    /** 状态：启用。 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用。 */
    public static final int STATUS_DISABLED = 1;

    @TableId
    private Long id;
    /** 引用编码，规则配置以此引用 */
    private String keyCode;
    /** 名称 */
    private String keyName;
    /** 提供方：KMS、VAULT、KEY_CENTER */
    private String provider;
    /** 密钥类型：SM2_KEY_PAIR、SM4_SYMMETRIC */
    private String keyType;
    /** 外部密钥标识；KEY_CENTER 为派生盐（随机生成，不可修改） */
    private String externalKeyId;
    /** 创建时的主密钥版本（仅展示；加密以配置的当前版本为准） */
    private String keyVersion;
    /** 非敏感扩展元数据 JSON（jsonb，由数据库默认值维护，应用不写入） */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String metadataJson;
    /** 状态：0 启用、1 停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
