package cn.iocoder.yudao.module.dataforge.dal.dataobject.driver;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据源驱动管理 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_datasource_driver")
@KeySequence("datahub_datasource_driver_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceDriverDO extends BaseDO {

    /**
     * 驱动编号
     */
    @TableId
    private Long id;

    /**
     * 驱动编码
     */
    private String driverCode;

    /**
     * 驱动名称
     */
    private String driverName;

    /**
     * 数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据
     *
     * 枚举 {@link TODO datahub_product_type 对应的类}
     */
    private String productType;

    /**
     * 驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据
     *
     * 枚举 {@link TODO datahub_driver_source 对应的类}
     */
    private String driverSource;

    /**
     * 驱动类名
     */
    private String driverClassName;

    /**
     * JDBC URL 模板
     */
    private String jdbcUrlTemplate;

    /**
     * 默认连接测试 SQL
     */
    private String testSql;

    /**
     * 连接图标路径
     */
    private String icon;

    /**
     * 是否有 schema 概念：1有 0无
     */
    private Short supportSchema;

    /**
     * 上传驱动 JAR 文件名称（内置为空）
     */
    private String jarFileName;

    /**
     * 上传驱动 JAR 文件路径（内置为空，走 classpath）
     */
    private String jarFilePath;

    /**
     * 上传驱动 JAR 文件 MD5
     */
    private String jarFileMd5;

    /**
     * 上传驱动 JAR 文件大小
     */
    private Double jarFileSize;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;


}
