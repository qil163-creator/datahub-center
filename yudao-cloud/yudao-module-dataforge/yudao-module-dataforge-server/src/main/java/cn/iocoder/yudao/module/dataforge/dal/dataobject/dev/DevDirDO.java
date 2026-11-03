package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据开发-任务目录 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_dev_dir")
@KeySequence("dh_dataforge_dev_dir_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevDirDO extends BaseDO {

    public static final Long PARENT_ROOT = 0L;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级编号，0 表示顶级目录
     */
    private Long parentId;

    /**
     * 目录名称
     */
    private String name;

    /**
     * 排序
     */
    private Integer sort;

    /** 0 启用，1 禁用；禁用目录不接收新的任务。 */
    private Integer status;

    private String description;

    private String remark;

}
