package cn.iocoder.yudao.module.supply.dal.dataobject.appapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 与本地权限变更同事务追加的 ETL 授权历史，不使用来源协议操作台账。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplyEtlGrantAuditDO extends BaseDO {

    private Long id;

    private Long grantId;

    private Long appId;

    private Long apiId;

    private Long actorId;

    private Long revision;

    private String action;

    private String beforeJson;

    private String afterJson;

    private String reason;

    private String traceId;

    private String result;
}
