package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;

/**
 * 值处理器泛型接口,定义某一目标类型值写入 COPY 二进制流及计算字节长度的方法。
 */
public interface IValueHandler<TTargetType> extends ValueHandler {

    /**
     * 按当前值类型将数据编码到目标输出端。
     */
    void handle(DataOutputStream buffer, final TTargetType value);

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    int getLength(final TTargetType value);
}
