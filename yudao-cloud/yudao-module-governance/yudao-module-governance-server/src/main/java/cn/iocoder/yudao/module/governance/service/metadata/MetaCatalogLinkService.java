package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkCommandDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCatalogLinkReceiptMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 元数据拥有挂接归属；回执、来源版本与挂接状态同一事务写入。 */
@Service
@RequiredArgsConstructor
public class MetaCatalogLinkService {

    private final MetaCatalogLinkReceiptMapper receipts;

    private final MetaTableMapper tables;

    /**
     * 应用元数据目录关联配置或已校验的变更。
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public MetadataCatalogLinkResultDTO apply(MetadataCatalogLinkCommandDTO command) {
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(command));
        MetaTableDO table = receipts.lockTable(command.tableId());
        MetadataCatalogLinkResultDTO known = receipts.receipt(command.operationId());
        if (known != null) {
            return Objects.equals(known.getRequestHash(), hash) ? known
                    : new MetadataCatalogLinkResultDTO(command.operationId(), hash, command.sourceRevision(),
                    "REJECTED", "OPERATION_CONFLICT");
        }
        String reason = null;
        Long revision = receipts.revision(command.sourceKey(), command.tableId());
        if (table == null && command.linked()) {
            reason = "TABLE_NOT_FOUND";
        } else if (revision != null && command.sourceRevision() <= revision) {
            reason = "STALE_SOURCE_REVISION";
        } else if (table != null && command.linked() && Integer.valueOf(1).equals(table.getCatalogLinked())
                && !Objects.equals(table.getCatalogItemUrn(), command.sourceKey())) {
            reason = "CATALOG_CONFLICT";
        }
        if (reason == null) {
            // 解除只清理本来源，绝不清理已由其他目录拥有的技术表。
            if (table != null && (command.linked() || Objects.equals(table.getCatalogItemUrn(), command.sourceKey()))) {
                if (tables.update(null, Wrappers.lambdaUpdate(MetaTableDO.class)
                        .set(MetaTableDO::getCatalogLinked, command.linked() ? 1 : 0)
                        .set(MetaTableDO::getCatalogItemUrn, command.linked() ? command.sourceKey() : null)
                        .eq(MetaTableDO::getId, command.tableId())) != 1) {
                    throw new IllegalStateException("Catalog link was not persisted");
                }
            }
        }
        if (revision == null || command.sourceRevision() > revision) {
            if (receipts.advance(command) != 1) {
                throw new IllegalStateException("目录来源版本并发变化，请核查原回执");
            }
        }
        var result = new MetadataCatalogLinkResultDTO(command.operationId(), hash, command.sourceRevision(),
                reason == null ? "APPLIED" : "REJECTED", reason);
        if (receipts.insert(command, result) != 1) {
            throw new IllegalStateException("目录回执未完整保存");
        }
        return result;
    }

    /**
     * 解析元数据目录关联查询对应的精确表及路由。
     */
    public MetadataCatalogLinkResultDTO query(String operationId) {
        var result = receipts.receipt(operationId);
        return result != null ? result : new MetadataCatalogLinkResultDTO(operationId, null, null, "NOT_FOUND", null);
    }

    /**
     * 判断目录关联是否由当前平台操作管理。
     */
    public boolean managed(Long tableId) {
        receipts.lockTable(tableId);
        return receipts.managed(tableId);
    }
}
