package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyEtlGrantAuditPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyEtlGrantAuditPageRespVO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyEtlGrantAuditMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/** 平台内应用授权管理员的只读审计；无需授权仍有效，已撤销记录仍可核查。 */
@Service
public class SupplyEtlGrantHistoryService {

    @Resource
    private SupplyEtlGrantAuditMapper audits;

    /**
     * 读取供应ETL授权历史历史记录。
     *
     * @param request 请求参数
     * @return 供应ETL授权审计分页响应数据
     */
    public SupplyEtlGrantAuditPageRespVO history(SupplyEtlGrantAuditPageReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw new IllegalStateException("Login user required");
        }
        if (request == null || request.getAppId() == null || request.getAppId() <= 0
                || request.getLimit() == null || request.getLimit() < 1 || request.getLimit() > 50
                || (request.getGrantId() != null && request.getGrantId() <= 0)
                || (request.getBeforeId() != null && request.getBeforeId() <= 0)) {
            throw new IllegalArgumentException("Bounded grant history required");
        }
        var rows = audits.history(request.getAppId(), request.getGrantId(), request.getBeforeId(),
                request.getLimit() + 1);
        boolean hasMore = rows.size() > request.getLimit();
        var items = rows.stream().limit(request.getLimit()).map(row -> {
            var item = BeanUtils.toBean(row, SupplyEtlGrantAuditPageRespVO.Item.class);
            item.setBefore(JsonUtils.parseObject(row.getBeforeJson(), SupplyEtlGrantAuditPageRespVO.Snapshot.class));
            item.setAfter(JsonUtils.parseObject(row.getAfterJson(), SupplyEtlGrantAuditPageRespVO.Snapshot.class));
            return item;
        }).toList();
        return new SupplyEtlGrantAuditPageRespVO(items, hasMore ? items.get(items.size() - 1).getId() : null, hasMore);
    }
}
