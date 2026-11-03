package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

/** 由部署持有的具名制品包，独立于业务数据源记录。 */
public interface DriverProfileSpec extends DriverBundleSpec {

    /**
     * 返回数据库产品。
     */
    ProductTypeEnum getProduct();

    /**
     * 判断默认对应产品。
     */
    boolean isDefaultForProduct();
}
