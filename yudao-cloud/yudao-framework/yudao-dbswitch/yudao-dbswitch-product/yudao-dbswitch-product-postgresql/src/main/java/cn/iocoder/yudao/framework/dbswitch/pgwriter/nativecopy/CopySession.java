package cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy;

import java.sql.SQLException;

/** 与驱动无关的 COPY 协议边界；协议结束不能证明事务已提交。 */
public interface CopySession {

    /**
     * 写入目标COPY。
     */
    void writeToCopy(byte[] bytes, int offset, int length) throws SQLException;

    /**
     * 刷新COPY。
     */
    void flushCopy() throws SQLException;

    /**
     * 结束当前 PostgreSQL COPY 会话并取得写入结果。
     */
    long endCopy() throws SQLException;

    /**
     * 取消COPY。
     */
    void cancelCopy() throws SQLException;

    /**
     * 判断活跃。
     */
    boolean isActive() throws SQLException;

    /**
     * 返回当前处理器累计处理的数据行数。
     */
    long getHandledRowCount() throws SQLException;
}
