package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;

/**
 * 集合处理器,将集合按 PostgreSQL 一维数组的二进制格式写入 COPY 流,元素由指定的内部处理器序列化。
 */
public class CollectionValueHandler<TElementType, TCollectionType extends Collection<TElementType>> extends
        BaseValueHandler<TCollectionType> {

    private final int oid;

    private final IValueHandler<TElementType> valueHandler;

    /**
     * 初始化 {@code CollectionValueHandler}。
     */
    public CollectionValueHandler(int oid, IValueHandler<TElementType> valueHandler) {
        this.oid = oid;
        this.valueHandler = valueHandler;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, TCollectionType value) throws IOException {

        ByteArrayOutputStream byteArrayOutput = new ByteArrayOutputStream();
        DataOutputStream arrayOutput = new DataOutputStream(byteArrayOutput);

        arrayOutput.writeInt(1); // 维数，当前一维数组使用 1
        arrayOutput.writeInt(1); // 数组可能包含空值
        arrayOutput.writeInt(oid); // 使用 OID 写入值
        arrayOutput.writeInt(value.size()); // 写入元素数量
        arrayOutput.writeInt(1); // 忽略下界，当前使用 PG 默认值

        // 使用内部处理器写入实际集合元素：
        for (TElementType element : value) {
            valueHandler.handle(arrayOutput, element);
        }

        buffer.writeInt(byteArrayOutput.size());
        buffer.write(byteArrayOutput.toByteArray());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(TCollectionType value) {
        throw new UnsupportedOperationException();
    }
}
