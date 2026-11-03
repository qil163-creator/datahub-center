package cn.iocoder.yudao.framework.dbswitch.pgwriter.function;

/**
 * 返回基本类型 float 的函数式接口,列映射中用于免装箱地提取实体的单精度浮点字段值。
 */
@FunctionalInterface
public interface ToFloatFunction<T> {

    /**
     * 将此函数应用于给定参数。
     *
     * @param value 函数参数
     * @return 函数结果
     */
    float applyAsFloat(T value);
}
