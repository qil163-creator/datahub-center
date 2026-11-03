package cn.iocoder.yudao.module.supply.dal.dataobject.appapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据服务-应用 API 授权 DO
 *
 * @author Li
 */
@TableName("dh_supply_app_api")
@KeySequence("dh_supply_app_api_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppApiDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 应用编号
     */
    private Long appId;

    /**
     * API服务编号
     */
    private Long apiId;

    /**
     * 是否永久有效：0-否 1-是
     */
    private Integer foreverFlag;

    /**
     * 有效期开始日期
     */
    private LocalDate startDate;

    /**
     * 有效期结束日期
     */
    private LocalDate endDate;

    /**
     * 描述
     */
    private String description;

    /**
     * 应用授权绑定的执行用户（集市申请人），用于主体校验、消费身份及审计；不得回退创建人。
     */
    private Long permissionUserId;

    /**
     * 备注
     */
    private String remark;

    private String sourceType;

    private String sourceId;

    private String purpose;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private Integer grantStatus;

    private Integer grantRevision;

    private String approvedContractHash;

    private String apiVersion;

    private Integer protocolVersion;

    private Long aggregateRevision;

    private Boolean aggregateDisabled;

    private String migrationStatus;

}
