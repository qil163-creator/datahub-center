package cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据库类型定义 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_database_type")
@KeySequence("dh_foundation_database_type_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseTypeDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 数据源编码
     */
    private String typeCode;

    /**
     * 数据源名称
     */
    private String typeName;

    /**
     * 连接类型描述
     */
    private String description;

    /**
     * 连接分类
     *
     * 枚举 {@link TODO category_code 对应的类}
     */
    private String categoryCode;

    /**
     * 连接协议类型
     *
     * 枚举 {@link TODO protocol_type 对应的类}
     */
    private String protocolType;

    /**
     * 连接图标路径
     */
    private String iconPath;

    /**
     * 数据源URL
     */
    private String urlTemplates;

    /**
     * 默认端口
     */
    private String portTemplates;

    /**
     * 默认连接测试SQL
     */
    private String validationQuery;

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
