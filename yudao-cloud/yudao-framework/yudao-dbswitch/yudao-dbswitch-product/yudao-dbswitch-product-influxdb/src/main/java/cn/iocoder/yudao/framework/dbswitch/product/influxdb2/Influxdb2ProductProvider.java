package cn.iocoder.yudao.framework.dbswitch.product.influxdb2;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;

import java.util.Collection;
import java.util.List;

/** 2.x HTTP 测连与 3.x Flight SQL 分离。 */
public final class Influxdb2ProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.INFLUXDB2)
                .connect(new Influxdb2ConnectionProbe()).build());
    }
}
