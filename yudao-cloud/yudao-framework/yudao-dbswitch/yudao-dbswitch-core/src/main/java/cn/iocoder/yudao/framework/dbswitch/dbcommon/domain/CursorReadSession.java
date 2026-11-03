package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;

import java.sql.SQLException;

/** 持有源快照和流式 ResultSet，直到 close 实际返回。 */
public interface CursorReadSession extends AutoCloseable {

    /**
     * 返回当前对象的结构指纹。
     */
    String schemaFingerprint();

    /**
     * 返回当前游标键契约的原生指纹。
     */
    String keyContractFingerprint();

    /**
     * 返回游标读写使用的结构化键契约。
     */
    default CursorKeyContract keyContract() {
        throw new UnsupportedOperationException("Structured cursor key evidence is unavailable");
    }

    /**
     * 返回当前读取范围的固定上界。
     */
    TypedCursor upperBound();

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    boolean next() throws SQLException;

    /**
     * 返回数据行。
     */
    Object[] row();

    /**
     * 返回当前游标或输入流的位置。
     */
    TypedCursor position();

    /**
     * 关闭游标读取状态并释放其持有的结果集及相关资源。
     */
    @Override
    void close() throws SQLException;
}
