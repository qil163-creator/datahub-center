package cn.iocoder.yudao.framework.etl.core.connection;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import lombok.Data;

/**
 * ETL 引擎使用的数据源连接信息（由业务侧解析注入，引擎不感知数据源管理）
 *
 * @author Li
 */
@Data
public class EtlConnectionInfo {

    /** 由所有者提供的不透明源配置修订号；缺少修订号时无法证明历史身份。 */
    private String configurationRevision;

    private Long databaseId;

    private String name;

    private ProductTypeEnum productType;

    private String driverProfileId;

    private String jdbcUrl;

    private String username;

    private String password;

    private String schema;

}
