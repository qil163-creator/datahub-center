package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 平台内例外到期扫描位置，不代表提交已处理或已成功。 */
@TableName("dh_governance_quality_exception_expiry_cursor")
@KeySequence("dh_governance_quality_exception_expiry_cursor_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityExceptionExpiryCursorDO extends BaseDO {

    @TableId
    private Long id;

    private Long afterSubmissionId;

    private Long throughSubmissionId;
}
