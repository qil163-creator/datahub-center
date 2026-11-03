package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter;

/**
 * 值转换器接口,把 Java 源类型的值转换为 COPY 二进制写入所需的目标类型。
 */
public interface IValueConverter<TSource, TTarget> {

    /**
     * 按目标类型约定转换当前输入值。
     */
    TTarget convert(TSource source);

}
