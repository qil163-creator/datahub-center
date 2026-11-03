package cn.iocoder.yudao.framework.dbswitch.dbchange;

import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;

/** 已有临时差异暂存委托给共享的有界类型化行格式。 */
final class DiffRowCodec {

    private final BoundedRowCodec delegate;

    /**
     * 初始化 {@code DiffRowCodec}。
     */
    DiffRowCodec(int columns, long maxBytes) {
        delegate = new BoundedRowCodec(columns, maxBytes);
    }

    /**
     * 将当前数据编码为字节。
     */
    byte[] encode(Object[] row) {
        return delegate.encode(row);
    }

    /**
     * 从编码数据恢复对象。
     */
    Object[] decode(byte[] bytes) {
        return delegate.decode(bytes);
    }
}
