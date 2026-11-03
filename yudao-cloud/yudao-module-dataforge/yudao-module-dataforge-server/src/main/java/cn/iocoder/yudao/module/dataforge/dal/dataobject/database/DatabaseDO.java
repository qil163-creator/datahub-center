package cn.iocoder.yudao.module.dataforge.dal.dataobject.database;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseConnectionStatusEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据源中心--数据库信息 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_database")
@KeySequence("dh_foundation_database_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 连接名称
     */
    private String name;

    /**
     * 连接类型
     */
    private Integer databaseType;

    /**
     * 主机ip地址
     */
    private String databaseIp;

    /**
     * 端口
     */
    private String databasePort;

    /**
     * 库名
     */
    private String databaseName;

    /**
     * 数据库模式（schema）
     */
    private String databaseSchema;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Integer status;

    /**
     * 用途分类：1-业务库 2-中台库
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.database.DatabaseUsageEnum}
     */
    private Integer usageType;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 密码（使用 secretKey AES 加密存储）
     */
    private String password;

    /**
     * JDBC 连接地址（jdbcUrl）
     */
    private String jdbcUrl;

    /**
     * 单位id
     */
    private Long orgId;

    /**
     * 扩展配置
     */
    private String extendConfig;

    /**
     * 密码解密密钥
     */
    private String secretKey;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 备注
     */
    private String remark;

    /**
     * 连通状态
     *
     * 枚举 {@link DatabaseConnectionStatusEnum}
     */
    private Integer connectionStatus;

    /**
     * 最近一次连接测试时间
     */
    private LocalDateTime lastTestTime;

    /**
     * 最近一次连接测试结果说明
     */
    private String lastTestMsg;

}
