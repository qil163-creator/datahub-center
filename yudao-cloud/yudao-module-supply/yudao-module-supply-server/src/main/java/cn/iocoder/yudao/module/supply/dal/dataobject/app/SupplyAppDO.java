package cn.iocoder.yudao.module.supply.dal.dataobject.app;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据服务-应用 DO（对接第三方系统）
 *
 * @author Li
 */
@TableName("dh_supply_app")
@KeySequence("dh_supply_app_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyAppDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 应用名称
     */
    private String name;

    /**
     * 应用类型：Web/App/小程序
     */
    private String appType;

    /**
     * 应用图标地址
     */
    private String icon;

    /**
     * 应用 AppKey（认证标识）
     */
    private String appKey;

    /**
     * 应用秘钥 AppSecret（签名认证用）
     */
    private String secret;

    /**
     * 是否公开：0-非公开 1-公开
     */
    private Integer publicFlag;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

    private Long owningDeptId;

    private String systemCode;

    private Long ownerUserId;

    private String purpose;

    private String environment;

    /** 应用状态：1启用、0暂停、2撤销 */
    private Integer appStatus;

    private Integer credentialRevision;

    private Integer rowVersion;

}
