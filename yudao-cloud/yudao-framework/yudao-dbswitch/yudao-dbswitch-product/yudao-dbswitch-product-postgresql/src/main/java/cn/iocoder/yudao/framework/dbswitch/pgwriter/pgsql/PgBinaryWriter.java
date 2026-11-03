package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.BinaryWriteFailedException;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.IValueHandler;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * COPY 二进制协议写入器,负责向输出流写入协议头、行起始标记及各种基本类型的字段值,关闭时写入结束标记。
 */
public class PgBinaryWriter implements AutoCloseable {

    private final transient DataOutputStream buffer;

    /**
     * 使用给定参数初始化 {@code PgBinaryWriter}。
     */
    public PgBinaryWriter(final OutputStream out) {
        this(out, 65536);
    }

    /**
     * 初始化 {@code PgBinaryWriter}。
     */
    public PgBinaryWriter(final OutputStream out, final int bufferSize) {
        buffer = new DataOutputStream(new BufferedOutputStream(out, bufferSize));
        writeHeader();
    }

    /**
     * 启动数据行。
     */
    public void startRow(int numColumns) {
        try {
            buffer.writeShort(numColumns);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 按当前列映射及写入协议将数据行写入目标。
     */
    public <TTargetType> void write(final IValueHandler<TTargetType> handler,
            final TTargetType value) {
        handler.handle(buffer, value);
    }

    /**
     * 将基本类型 boolean 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeBoolean(boolean value) {
        try {
            buffer.writeInt(1);
            if (value) {
                buffer.writeByte(1);
            } else {
                buffer.writeByte(0);
            }
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }


    /**
     * 将基本类型 byte 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeByte(int value) {
        try {
            buffer.writeInt(1);
            buffer.writeByte(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将基本类型 short 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeShort(int value) {
        try {
            buffer.writeInt(2);
            buffer.writeShort(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将基本类型 int 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeInt(int value) {
        try {
            buffer.writeInt(4);
            buffer.writeInt(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将基本类型 long 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeLong(long value) {
        try {
            buffer.writeInt(8);
            buffer.writeLong(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将基本类型 float 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeFloat(float value) {
        try {
            buffer.writeInt(4);
            buffer.writeFloat(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 将基本类型 double 写入输出流
     *
     * @param value 待写入的值
     */
    public void writeDouble(double value) {
        try {
            buffer.writeInt(8);
            buffer.writeDouble(value);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 写入空值。
     */
    public void writeNull() {
        try {
            buffer.writeInt(-1);
        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 关闭当前写入器并释放其持有的写入资源。
     */
    @Override
    public void close() {
        try {
            buffer.writeShort(-1);

            buffer.flush();
            buffer.close();
        } catch (Exception e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }

    /**
     * 写入表头。
     */
    private void writeHeader() {
        try {

            // 必需的 11 字节文件头
            buffer.writeBytes("PGCOPY\n\377\r\n\0");
            // 表示不含 OID 的 32 位整数
            buffer.writeInt(0);
            // 32 位文件头扩展区长度
            buffer.writeInt(0);

        } catch (IOException e) {
            Throwable t = e.getCause();
            if (null != t) {
                throw new BinaryWriteFailedException(t);
            } else {
                throw new BinaryWriteFailedException(e);
            }
        }
    }
}
