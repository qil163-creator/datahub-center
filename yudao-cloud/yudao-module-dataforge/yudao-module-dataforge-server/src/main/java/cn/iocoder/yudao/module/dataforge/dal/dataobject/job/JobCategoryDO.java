package cn.iocoder.yudao.module.dataforge.dal.dataobject.job;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
/** 作业管理持久状态，不保存连接凭据。 */
@Data @EqualsAndHashCode(callSuper=true)
@TableName(value="dh_dataforge_job_category",autoResultMap=true)
@KeySequence("dh_dataforge_job_category_seq")
public class JobCategoryDO extends BaseDO { @TableId private Long id; private Long parentId; private String name; private Integer sort; }
