package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-附件 DO（附件、图片字段的单元格存附件编号；文件本体在 infra 受管文件中）
 *
 * 两阶段：先落 PENDING 意图，再上传受管文件并核对回执后置 READY；
 * 提交时绑定提交快照（不再过期），未被引用的附件过期后由清理任务按操作键对账删除。
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_attachment")
@KeySequence("dh_dataforge_fill_attachment_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillAttachmentDO extends BaseDO {

    /** 状态：上传意图已登记 */
    public static final String STATE_PENDING = "PENDING";
    /** 状态：受管文件已上传并核对回执 */
    public static final String STATE_READY = "READY";
    /** 状态：已删除（受管文件已删除或从未写入） */
    public static final String STATE_DELETED = "DELETED";

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 字段码
     */
    private String fieldCode;

    /**
     * 文件名
     */
    private String name;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private Long size;

    /**
     * 内容 SHA-256
     */
    private String sha256;

    /**
     * 受管文件操作键（全局唯一，用于幂等上传与对账）
     */
    private String operationKey;

    /**
     * 受管文件编号（READY 后写入）
     */
    private Long storageFileId;

    /**
     * 状态：PENDING / READY / DELETED
     */
    private String state;

    /**
     * 上传人编号
     */
    private Long uploaderUserId;

    /**
     * 绑定的提交快照编号（提交后写入，绑定后不再过期）
     */
    private Long submissionId;

    /**
     * 未绑定附件的过期时间（过期且草稿不再引用时清理）
     */
    private LocalDateTime expiresAt;

}
