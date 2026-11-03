package cn.iocoder.yudao.module.asset.dal.dataobject.dataquery;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据查询文件目录 DO：目录与 SQL 脚本共用一张表。
 */
@TableName("dh_asset_data_query_file")
@KeySequence("dh_asset_data_query_file_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataQueryFileDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    public static final int NODE_TYPE_FOLDER = 1;

    public static final int NODE_TYPE_FILE = 2;

    /** 公开：平台内可见 */
    public static final int VISIBILITY_PUBLIC = 1;

    /** 私有：仅创建人可见 */
    public static final int VISIBILITY_PRIVATE = 2;

    @TableId
    private Long id;

    private Long parentId;

    /**
     * 节点类型：1-目录 2-SQL 脚本
     */
    private Integer nodeType;

    private String name;

    private String sqlText;

    private Long datasourceId;

    private Integer queryLimit;

    /**
     * 可见范围：1-公开 2-私有
     */
    private Integer visibility;

    private Integer sort;

    private String remark;

}
