package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;

import java.util.List;
import java.util.Objects;

/**
 * 交付操作的冻结载荷：入库时固定命令内容与摘要，重试只重发原命令，
 * 不按之后被修改的申请重新组装。反序列化失败明确拒绝，不猜测原意。
 */
public final class MarketDeliveryPayload {

    /** 来源系统标识；必须与 Supply 端认证出的服务身份一致。 */
    public static final String SOURCE_SYSTEM = "asset";

    /**
     * 限制市场交付载荷工具类的直接实例化。
     */
    private MarketDeliveryPayload() {
    }

    /**
     * 转换JSON。
     */
    public static String toJson(SupplyGrantSourceCommandDTO command) {
        return JsonUtils.toJsonString(command);
    }

    /**
     * 解析JSON。
     */
    public static SupplyGrantSourceCommandDTO fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalStateException("交付操作缺少冻结载荷");
        }
        SupplyGrantSourceCommandDTO command =
                JsonUtils.parseObjectQuietly(payloadJson, SupplyGrantSourceCommandDTO.class);
        if (command == null) {
            throw new IllegalStateException("交付操作的冻结载荷无法解析");
        }
        return command;
    }

    /**
     * 计算市场交付载荷内容摘要。
     */
    public static String hash(SupplyGrantSourceCommandDTO command) {
        return SupplyGrantCommandCodec.hash(command);
    }

    /**
     * 只读展示原请求动作，而非当前授权或执行结果。历史 REVOKE 类型同时承载暂停和终态撤销，
     * 必须复用原始身份/摘要校验区分；缺少可信证据时不从类型或当前申请状态猜测。
     */
    static String operationAction(MarketDeliveryOperationDO row) {
        if (row == null || !Integer.valueOf(2).equals(row.getProtocolVersion())) {
            return "UNKNOWN";
        }
        try {
            var command = fromJson(row.getPayloadJson());
            return matchesFrozenOperation(row, command) ? command.action().name() : "UNKNOWN";
        } catch (RuntimeException invalidEvidence) {
            // 不记录载荷或解析异常；动作未知不等于撤销，也不补造/改写历史命令。
            return "UNKNOWN";
        }
    }

    /** 批准语义不随暂停/恢复的交付版本变化；其余字段均复用提供方规范编码。 */
    static String approvalHash(Long assetId, SupplyGrantSourceCommandDTO command) {
        if (assetId == null || assetId <= 0 || command == null || command.protocolVersion() != 2
                || command.action() != SupplyGrantSourceCommandDTO.Action.GRANT || command.scope() == null) {
            throw new IllegalArgumentException("缺少完整批准身份");
        }
        var normalized = new SupplyGrantSourceCommandDTO(2, "approval-evidence", command.sourceSystem(),
                command.sourceKey(), 1, command.appId(), command.apiId(), command.action(), command.scope(), null);
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of("asset-grant-approval/v1", assetId,
                SupplyGrantCommandCodec.hash(normalized))));
    }

    /** 执行、对账与只读证据核查共用同一原始身份校验，不从当前申请重建历史命令。 */
    static boolean matchesFrozenOperation(MarketDeliveryOperationDO row,
            SupplyGrantSourceCommandDTO command) {
        if (row.getApplyId() == null || row.getApplyId() <= 0 || row.getSourceRevision() == null
                || command.protocolVersion() != 2 || command.action() == null
                || !Objects.equals(row.getOperationId(), command.operationId())
                || !Objects.equals(row.getAppId(), command.appId())
                || !Objects.equals(row.getApiId(), command.apiId())
                || !Objects.equals(row.getSourceRevision(), command.sourceRevision())
                || !SOURCE_SYSTEM.equals(command.sourceSystem())
                || !Objects.equals("asset:apply:" + row.getApplyId(), command.sourceKey())
                // 历史存储契约：SUSPEND 与 REVOKE 都使用 REVOKE 类型；动作由冻结命令区分。
                || !Objects.equals(row.getOpType(), command.action() == SupplyGrantSourceCommandDTO.Action.GRANT
                ? MarketDeliveryOperationDO.TYPE_GRANT : MarketDeliveryOperationDO.TYPE_REVOKE)
                || command.action() == SupplyGrantSourceCommandDTO.Action.GRANT
                && !Objects.equals(row.getSourceGrantId(), command.scope().approvalRef())) {
            return false;
        }
        try {
            return Objects.equals(row.getPayloadHash(), hash(command));
        } catch (RuntimeException unusable) {
            return false;
        }
    }
}
