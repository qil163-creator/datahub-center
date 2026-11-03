package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.BinaryWriteFailedException;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 值处理器抽象基类,统一处理 null 值(写入 -1 长度)并包装 IO 异常,由子类实现具体类型的二进制写入。
 */
public abstract class BaseValueHandler<T> implements IValueHandler<T> {

    /**
     * 按当前值类型将数据编码到目标输出端。
     */
    @Override
    public void handle(DataOutputStream buffer, final T value) {
        try {
            if (value == null) {
                buffer.writeInt(-1);
                return;
            }
            internalHandle(buffer, value);
        } catch (IOException e) {
            if (null != e.getCause()) {
                throw new BinaryWriteFailedException(e.getCause());
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    protected abstract void internalHandle(DataOutputStream buffer, final T value) throws IOException;
}
