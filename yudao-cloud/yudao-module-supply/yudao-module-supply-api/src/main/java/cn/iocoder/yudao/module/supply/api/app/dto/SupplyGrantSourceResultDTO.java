package cn.iocoder.yudao.module.supply.api.app.dto;

/** 来源操作持久回执；NOT_FOUND 仅代表权威查询不存在，不能表示传输故障。 */
public record SupplyGrantSourceResultDTO(
        String operationId, Outcome outcome, String errorCode,
        long sourceRevision, long aggregateRevision, String payloadHash) {

    public enum Outcome {APPLIED, STALE, REJECTED, NOT_FOUND, UNKNOWN}
}
