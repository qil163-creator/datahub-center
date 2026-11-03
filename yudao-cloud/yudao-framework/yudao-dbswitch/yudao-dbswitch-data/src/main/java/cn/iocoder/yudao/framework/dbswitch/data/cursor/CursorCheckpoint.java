package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

/** 仅记录已应用的位置；收到或读取源端行都不会推进此记录。 */
public record CursorCheckpoint(int version, String bindingFingerprint, String schemaFingerprint,
                               String targetSchemaFingerprint,
                               TypedCursor fixedUpper, TypedCursor applied, long sequence,
                               boolean complete, TransactionCommitReceipt lastCommit,
                               CommitAcknowledgement lastCommitAcknowledgement,
                               ProductTypeEnum sourceProduct, ProductTypeEnum targetProduct) {

    /** V1 仅用于 PostgreSQL；解码时不生成新的数据库模式或路由标识。 */
    public CursorCheckpoint(int version, String bindingFingerprint, String schemaFingerprint,
            String targetSchemaFingerprint,
            TypedCursor fixedUpper, TypedCursor applied, long sequence, boolean complete,
            TransactionCommitReceipt lastCommit) {
        this(version, bindingFingerprint, schemaFingerprint, targetSchemaFingerprint, fixedUpper, applied, sequence,
                complete, lastCommit,
                lastCommit == null ? CommitAcknowledgement.NONE : CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,
                ProductTypeEnum.POSTGRESQL, ProductTypeEnum.POSTGRESQL);
    }

    /**
     * 校验构造参数并初始化 {@code CursorCheckpoint}。
     */
    public CursorCheckpoint {
        if (version == 1) {
            if (sourceProduct != null && sourceProduct != ProductTypeEnum.POSTGRESQL
                    || targetProduct != null && targetProduct != ProductTypeEnum.POSTGRESQL
                    || lastCommitAcknowledgement != null && lastCommitAcknowledgement != (lastCommit == null
                    ? CommitAcknowledgement.NONE : CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED)) {
                throw new IllegalArgumentException("V1 cursor checkpoint is exclusively PostgreSQL");
            }
            sourceProduct = ProductTypeEnum.POSTGRESQL;
            targetProduct = ProductTypeEnum.POSTGRESQL;
            lastCommitAcknowledgement = lastCommit == null ? CommitAcknowledgement.NONE : CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED;
        }
        if ((version != 1 && version != 2) || !hash(bindingFingerprint) || !hash(schemaFingerprint) || !hash(
                targetSchemaFingerprint) || sequence < 0
                || sourceProduct == null || targetProduct == null || sourceProduct == ProductTypeEnum.UNKNOWN || targetProduct == ProductTypeEnum.UNKNOWN
                || lastCommitAcknowledgement == null || applied != null && lastCommitAcknowledgement == CommitAcknowledgement.NONE
                || sequence > 0 && applied == null
                || version == 2 && ((applied == null) != (lastCommitAcknowledgement == CommitAcknowledgement.NONE)
                || (sequence == 0) != (applied == null) || applied != null && fixedUpper == null)
                || (lastCommit != null) != (lastCommitAcknowledgement == CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED)
                || lastCommit != null && targetProduct != ProductTypeEnum.POSTGRESQL
                || lastCommitAcknowledgement == CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE
                && targetProduct != ProductTypeEnum.DORIS && targetProduct != ProductTypeEnum.STARROCKS) {
            throw new IllegalArgumentException("Invalid applied cursor checkpoint");
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "CursorCheckpoint[version=" + version + ",sequence=" + sequence + ",complete=" + complete + "]";
    }
}
