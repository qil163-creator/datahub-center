package cn.iocoder.yudao.module.governance.dal.dataobject.doc;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 标准文件登记 DO
 *
 * @author Li
 */
@TableName("dh_governance_standard_doc")
@KeySequence("dh_standard_doc_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardDocDO extends BaseDO {

    /**
     * 标准文件编号
     */
    @TableId
    private Long id;

    /**
     * 标准编号
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String stdCode;

    /**
     * 标准名称
     */
    private String nameCn;

    /**
     * 标准英文名称（已废弃，前端不再采集）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String nameEn;

    /**
     * 标准分类
     *
     * 字典 standard_std_type
     */
    private String stdType;

    /** 标准类目，与数据元共用标准目录。 */
    private Long categoryId;

    /** 标准原文版本号，与平台版本快照编号无关。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String versionNo;

    /** 废止日期。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate abolishDate;

    /**
     * 发布机构
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String publishOrg;

    /**
     * 发布日期
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate publishDate;

    /**
     * 实施日期
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate implementDate;

    /**
     * 实施状态
     *
     * 字典 standard_implement_status
     */
    private String implementStatus;

    /**
     * 入库编号
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String registryNo;

    /**
     * 入库时间
     */
    private LocalDateTime registryTime;

    /**
     * 标准原文文件编号
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long fileId;

    /**
     * 标准原文文件名快照
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fileName;

    /**
     * 标准原文访问地址快照
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fileUrl;

    /**
     * 被本标准替代的旧标准编号
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long replaceDocId;

    /**
     * 标准摘要/适用范围说明
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    /**
     * 状态
     *
     * 字典 common_status
     */
    private Short status;

    /**
     * 显示顺序
     */
    private Integer sort;

    /**
     * 备注
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

}
