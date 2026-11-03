package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 板块级逻辑表推荐名称配置，不执行数据库 DDL。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数据板块逻辑表命名规则")
public class DataDomainNamingRuleVO {

    @NotBlank
    @Pattern(regexp = "ODS|DIM|FACT_PROCESS|FACT_EVENT|DWS|ADS")
    private String tableType;

    @NotBlank
    @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,31}")
    private String prefix;

    @Size(max = 512, message = "时间后缀不能超过 512 个字符")
    private String daySuffix;

    @Size(max = 512, message = "时间后缀不能超过 512 个字符")
    private String hourSuffix;

    @Size(max = 512, message = "时间后缀不能超过 512 个字符")
    private String minuteSuffix;

    @NotNull
    private Boolean includeSubjectAbbreviation;

    /**
     * 获取数据域命名规则的默认值。
     *
     * @return 数据域命名规则的默认值结果集合
     */
    public static List<DataDomainNamingRuleVO> defaults() {
        return new ArrayList<>(List.of(
                new DataDomainNamingRuleVO("ODS", "ods_", "", "", "", false),
                new DataDomainNamingRuleVO("FACT_PROCESS", "dwd_", "_da", "", "", true),
                new DataDomainNamingRuleVO("FACT_EVENT", "dwd_", "_di", "_hi", "_mi", true),
                new DataDomainNamingRuleVO("DWS", "dws_", "", "_hh", "_mm", false)));
    }

    /**
     * 获取数据域命名规则的详情默认值。
     *
     * @return 数据域命名规则的详情默认值结果集合
     */
    public static List<DataDomainNamingRuleVO> detailDefaults() {
        var result = defaults();
        result.add(1, new DataDomainNamingRuleVO("DIM", "dim_", "", "_hf", "_mf", false));
        result.add(new DataDomainNamingRuleVO("ADS", "ads_", "", "", "", false));
        return result;
    }
}
