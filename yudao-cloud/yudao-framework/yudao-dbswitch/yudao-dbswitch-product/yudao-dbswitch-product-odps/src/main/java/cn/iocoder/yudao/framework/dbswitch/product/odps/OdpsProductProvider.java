package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;

import java.util.Collection;
import java.util.List;

/** 独立连接探测与只读 SDK 目录；不声明 JDBC、SQL 或数据读写能力。 */
public final class OdpsProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.ODPS)
                .connect(new OdpsConnectionProbe()).catalogMetadata(new OdpsCatalogMetadata()).build());
    }
}
