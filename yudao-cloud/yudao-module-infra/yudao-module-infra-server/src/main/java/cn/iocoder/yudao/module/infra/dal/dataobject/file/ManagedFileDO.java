package cn.iocoder.yudao.module.infra.dal.dataobject.file;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 受管文件跨存储与数据库两阶段操作的持久回执。 */
@TableName("infra_managed_file")
@KeySequence("infra_managed_file_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class ManagedFileDO extends BaseDO {
    private Long id;
    private String ownerService;
    private String operationKey;
    private String name;
    private String sha256;
    private Long size;
    private Long configId;
    private String path;
    private String state;
    private Long fileId;
}
