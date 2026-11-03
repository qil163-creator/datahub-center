package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.DataType;

/**
 * 值处理器提供者接口,按 PostgreSQL 数据类型解析出对应的值处理器。
 */
public interface IValueHandlerProvider {

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    <TTargetType> IValueHandler<TTargetType> resolve(DataType targetType);
}
