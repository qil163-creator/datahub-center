package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 可视化 ETL 工具箱组件 DO
 *
 * 参考 srt-cloud 的 data_prod_tools_category：目录树 + 节点组件定义
 *
 * @author Li
 */
@TableName("dh_dataforge_etl_tool")
@KeySequence("dh_dataforge_etl_tool_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtlToolDO extends BaseDO {

    /** 目录节点 */
    public static final int TYPE_FOLDER = -1;

    /**
     * 主键（内置组件使用固定编号）
     */
    @TableId
    private Long id;

    /**
     * 父级编号，0 表示根
     */
    private Long parentId;

    /**
     * 名称
     */
    private String name;

    /**
     * 图标
     */
    private String icon;

    /**
     * 类型：-1 目录 1 输入 2 转换 3 输出 4 脚本
     */
    private Integer type;

    /**
     * 组件代码（决定执行器与前端编辑器），目录为空
     */
    private String code;

    /**
     * 是否内置：0-否 1-是
     */
    private Integer builtIn;

    /**
     * 是否启用：0-禁用 1-启用
     */
    private Integer enabled;

    /**
     * 排序号
     */
    private Integer orderNo;

    /**
     * 描述
     */
    private String description;

}
