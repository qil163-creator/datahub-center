package cn.iocoder.yudao.framework.dbswitch.product.milvus;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;

import java.util.Collection;
import java.util.List;

/** 只注册独立连接探测，其余操作明确拒绝。 */
public final class MilvusProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.MILVUS)
                .connect(new MilvusConnectionProbe()).build());
    }
}
