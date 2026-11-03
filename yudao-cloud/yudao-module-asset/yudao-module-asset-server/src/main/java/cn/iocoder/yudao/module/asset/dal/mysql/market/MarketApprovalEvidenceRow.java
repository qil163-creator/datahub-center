package cn.iocoder.yudao.module.asset.dal.mysql.market;

import lombok.Data;

/** 原批准摘要的内部投影，不向管理端返回字段或冻结命令。 */
@Data
public class MarketApprovalEvidenceRow {

    private Long id;

    private Long applyId;

    private String approvalRef;

    private Long approvalAssetId;

    private String approvalScopeHash;
}
