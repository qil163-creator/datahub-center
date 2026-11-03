package cn.iocoder.yudao.framework.dbswitch.spi;

import java.util.Collection;

/** 标准 ServiceLoader SPI。发现不产生可变工厂注册副作用。 */
public interface DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    Collection<DbSwitchProductDescriptor> products();
}
