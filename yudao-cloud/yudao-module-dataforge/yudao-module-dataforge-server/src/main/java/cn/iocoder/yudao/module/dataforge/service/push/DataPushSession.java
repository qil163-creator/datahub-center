package cn.iocoder.yudao.module.dataforge.service.push;

/**
 * 一次推送执行的通道会话：连接复用，停止时可立即断开
 *
 * @author Li
 */
public interface DataPushSession extends AutoCloseable {

    DataPushSession NOOP = new DataPushSession() {
        /**
         * 中止数据推送会话。
         */
        @Override
        public void abort() {
        }

        /**
         * 关闭当前实例持有的连接、会话或执行资源。
         */
        @Override
        public void close() {
        }
    };

    /**
     * 立即打断当前阻塞 I/O（关闭连接 / 取消 HTTP 请求）
     */
    void abort();

    /**
     * 关闭当前实例持有的连接、会话或执行资源。
     */
    @Override
    void close();

}
