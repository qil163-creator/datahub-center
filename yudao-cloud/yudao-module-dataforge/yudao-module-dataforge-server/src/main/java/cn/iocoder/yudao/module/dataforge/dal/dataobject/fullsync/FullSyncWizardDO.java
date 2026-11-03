package cn.iocoder.yudao.module.dataforge.dal.dataobject.fullsync;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 整库同步四步向导草稿及外部调度提交状态。
 *
 * <p>仅保存业务配置和外部引用编号，不保存平台数据源密码。</p>
 */
@TableName(value = "dh_dataforge_full_sync_wizard", autoResultMap = true)
@KeySequence("dh_dataforge_full_sync_wizard_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class FullSyncWizardDO extends BaseDO {

    @TableId
    private Long id;

    private Integer currentStep;

    private Long revision;

    private String status;

    private String executionMode;

    private Long dataAccessId;

    private Long dsProjectCode;

    private String dsWorkerGroup;

    private String dsTenantCode;

    private Long dsSourceDatasourceCode;

    private Long dsTargetDatasourceCode;

    private Long dsWorkflowCode;

    private Integer dsScheduleId;

    private Integer dsInstanceId;

    private String submitRequestId;

    private String lastError;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String configJson;
}

