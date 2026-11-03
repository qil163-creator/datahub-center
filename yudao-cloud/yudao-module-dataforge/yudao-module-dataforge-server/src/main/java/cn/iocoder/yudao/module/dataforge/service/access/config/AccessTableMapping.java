package cn.iocoder.yudao.module.dataforge.service.access.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 单表业务规则；列元数据、运行水位和回调只允许存在于运行配置中。 */
@Data
public class AccessTableMapping {

    /** 可选的最终物理表名；设置时替代正则、ODS 前缀和大小写规则。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String targetTableName;

    /** null 沿用源表注释，空串显式清除目标表注释。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String targetTableComment;

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private AccessDorisOptions dorisOptions;

    private List<AccessNameMapping> regexTableMapper = new ArrayList<>();

    private List<AccessNameMapping> regexColumnMapper = new ArrayList<>();

    private List<String> sourcePrimaryKeys = new ArrayList<>();

    private String timestampColumn;

    private String increaseColumnName;
}
