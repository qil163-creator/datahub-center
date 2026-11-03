package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.*;

/** 会话本地授权凭据，绝不序列化或持久化；取消或过期时清除。 */
final class EtlPreviewResultGuard {

    private record Sample(String node, Long database, String sql, String originalSql,
                          List<Map<String, Object>> raw, String safeHash, EtlReadPolicyPort policy) {

    }

    private final List<Sample> samples = new ArrayList<>();

    private long bytes;

    private boolean closed;

    private Runnable check;

    /**
     * 设置结果读取时需要执行的当前权限校验，拒绝修改已关闭的预览。
     *
     * @param check 检查
     */
    synchronized void currentAccessCheck(Runnable check) {
        if (closed) {
            throw new IllegalStateException("Closed preview");
        }
        this.check = check;
    }

    /**
     * 包装ETL 预览结果门禁。
     *
     * @param delegate 委托
     * @return ETL读取策略端口
     */
    EtlReadPolicyPort wrap(EtlReadPolicyPort delegate) {
        return new EtlReadPolicyPort() {
            /**
             * 获取ETL 预览结果门禁的重写。
             *
             * @param node 节点
             * @param database 数据源
             * @param sql SQL
             * @return ETL 预览结果门禁的重写文本
             */
            public String rewrite(String node, Long database, String sql) {
                return delegate.rewrite(node, database, sql);
            }

            /**
             * 按条件筛选ETL 预览结果门禁。
             *
             * @param node 节点
             * @param database 数据源
             * @param sql SQL
             * @param rows 待处理的行数据
             * @return 按条件筛选ETL 预览结果门禁结果集合
             */
            public List<Map<String, Object>> filter(String node, Long database, String sql,
                    List<Map<String, Object>> rows) {
                return filter(node, database, sql, rows, sql);
            }

            /**
             * 按条件筛选ETL 预览结果门禁。
             *
             * @param node 节点
             * @param database 数据源
             * @param sql SQL
             * @param rows 待处理的行数据
             * @param original 原始记录
             * @return 按条件筛选ETL 预览结果门禁结果集合
             */
            public List<Map<String, Object>> filter(String node, Long database, String sql,
                    List<Map<String, Object>> rows, String original) {
                // 首次校验及后续每次权限校验均复用相同的传输表示。
                List<Map<String, Object>> raw = copy(rows);
                synchronized (EtlPreviewResultGuard.this) {
                    if (closed || samples.size() >= 32) {
                        throw new IllegalStateException("Preview evidence unavailable");
                    }
                    for (var row : raw) {
                        bytes += EtlRunContext.estimateRowBytes(row);
                    }
                    if (bytes > 10L * 1024 * 1024) {
                        throw new IllegalStateException("Preview evidence budget exceeded");
                    }
                }
                var safe = delegate.filter(node, database, sql, copy(raw), original);
                if (safe == null) {
                    throw new IllegalStateException("Permission result unavailable");
                }
                synchronized (EtlPreviewResultGuard.this) {
                    if (closed) {
                        throw new IllegalStateException("Closed preview");
                    }
                    samples.add(new Sample(node, database, sql, original, raw, hash(safe), delegate));
                }
                return safe;
            }
        };
    }

    /**
     * 核对ETL 预览结果门禁。
     */
    void verify() {
        final List<Sample> snapshot;
        final Runnable access;
        synchronized (this) {
            if (closed || check == null) {
                throw new IllegalStateException("Preview authorization unavailable");
            }
            snapshot = List.copyOf(samples);
            access = check;
        }
        access.run();
        for (var sample : snapshot) {
            if (!Objects.equals(sample.sql(),
                    sample.policy().rewrite(sample.node(), sample.database(), sample.originalSql()))) {
                throw new IllegalStateException("Preview row policy changed");
            }
            var safe = sample.policy()
                    .filter(sample.node(), sample.database(), sample.sql(), copy(sample.raw()), sample.originalSql());
            if (safe == null || !sample.safeHash().equals(hash(safe))) {
                throw new IllegalStateException("Preview visibility changed");
            }
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Closed preview");
            }
        }
    }

    /**
     * 清理ETL 预览结果门禁。
     */
    synchronized void clear() {
        closed = true;
        samples.clear();
        check = null;
        bytes = 0;
    }

    /**
     * 计算输入内容摘要。
     *
     * @param rows 待处理的行数据
     * @return 计算后的摘要或签名字符串
     */
    private static String hash(List<Map<String, Object>> rows) {
        return DigestUtil.sha256Hex(EtlJsonValues.write(rows));
    }

    /**
     * 复制ETL 预览结果门禁。
     *
     * @param rows 待处理的行数据
     * @return 复制ETL 预览结果门禁结果集合
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> copy(List<Map<String, Object>> rows) {
        return (List<Map<String, Object>>) (List<?>) EtlJsonValues.parse(EtlJsonValues.write(rows));
    }
}
