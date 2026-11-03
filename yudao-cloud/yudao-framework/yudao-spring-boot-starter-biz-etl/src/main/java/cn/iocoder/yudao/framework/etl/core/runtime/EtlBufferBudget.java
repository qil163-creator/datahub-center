package cn.iocoder.yudao.framework.etl.core.runtime;

import java.util.Map;

/** 节点线程持有的中间缓存租约，在保存派生行或索引前申请，在释放缓存时关闭。 */
public final class EtlBufferBudget implements AutoCloseable {

    private final EtlRunContext context;

    private final long maxRows;

    private long rows;

    private long bytes;

    private boolean closed;

    /**
     * 校验构造参数并初始化 {@code EtlBufferBudget}。
     */
    public EtlBufferBudget(EtlRunContext context, long maxRows) {
        this.context = context;
        this.maxRows = Math.min(maxRows, context.getLimits() == null ? 100_000 : context.getLimits().getMaxRows());
        if (this.maxRows <= 0) {
            throw new IllegalArgumentException("中间缓存行数预算无效");
        }
    }

    /**
     * 保留数据行。
     */
    public void retainRow(Map<String, Object> row) {
        long estimated = EtlRunContext.estimateRowBytes(row);
        accountCandidate();
        if (context.getLimits() != null && estimated > context.getLimits().getMaxRowBytes()) {
            throw new IllegalStateException("ETL 中间结果超过行数或单行字节预算");
        }
        reserve(estimated);
    }

    /** 去重也必须约束尝试展开的行，不能因最终输出少而绕过计算预算。 */
    public void accountCandidate() {
        if (closed) {
            throw new IllegalStateException("中间缓存预算已关闭");
        }
        if (rows >= maxRows) {
            throw new IllegalStateException("ETL 中间结果超过行数预算");
        }
        rows++;
    }

    /** 索引、去重键和引用的额外内存，不重复计算其指向的输入行。 */
    public void reserve(long estimatedBytes) {
        if (closed) {
            throw new IllegalStateException("中间缓存预算已关闭");
        }
        context.reserveBufferedBytes(estimatedBytes);
        bytes += estimatedBytes;
    }

    /**
     * 关闭 {@code EtlBufferBudget} 当前持有的资源。
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            context.releaseBufferedBytes(bytes);
        }
    }
}
