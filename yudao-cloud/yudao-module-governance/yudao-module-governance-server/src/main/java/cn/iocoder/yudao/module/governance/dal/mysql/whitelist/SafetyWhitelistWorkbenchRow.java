package cn.iocoder.yudao.module.governance.dal.mysql.whitelist;

import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyWhitelistWorkbenchRow extends SafetyWhitelistDO {

    private String categoryName;

    private String categorySourceRecordId;
}
