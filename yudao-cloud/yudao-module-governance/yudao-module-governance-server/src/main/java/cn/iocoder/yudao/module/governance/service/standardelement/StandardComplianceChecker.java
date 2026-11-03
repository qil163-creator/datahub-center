package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;

import java.util.ArrayList;
import java.util.List;

/**
 * 贯标比对器：比对落标字段的实际定义与标准数据元定义是否一致。
 *
 * <p>只比对标准侧已定义的约束；标准完全未定义约束时结果为 SKIP。
 * 字段侧对应属性缺失（如未采集到长度）时跳过该项，不作为不符合。</p>
 *
 * @author Li
 */
public final class StandardComplianceChecker {

    public static final String RESULT_PASS = "PASS";

    public static final String RESULT_FAIL = "FAIL";

    public static final String RESULT_SKIP = "SKIP";

    /**
     * 限制标准合规审核人工具类的直接实例化。
     */
    private StandardComplianceChecker() {
    }

    /**
     * 贯标比对结果
     *
     * @param result PASS/FAIL/SKIP（通过、失败或跳过）
     * @param issues 不符合项说明
     */
    public record ComplianceResult(String result, List<String> issues) {

    }

    /**
     * 比较标准数据元与实际字段的类型、长度和精度等属性，汇总不符合项。
     */
    public static ComplianceResult check(StandardElementDO element, MetaColumnDO column) {
        List<String> issues = new ArrayList<>();
        boolean anyChecked = false;
        if (StrUtil.isNotBlank(element.getColumnType()) && StrUtil.isNotBlank(column.getDataType())) {
            anyChecked = true;
            if (!element.getColumnType().equalsIgnoreCase(column.getDataType())) {
                issues.add(String.format("字段类型不符：标准 %s，实际 %s",
                        element.getColumnType(), column.getDataType()));
            }
        }
        if (element.getDataLength() != null && column.getDataLength() != null) {
            anyChecked = true;
            if (column.getDataLength() != element.getDataLength().longValue()) {
                issues.add(String.format("数据长度不符：标准 %d，实际 %d",
                        element.getDataLength(), column.getDataLength()));
            }
        }
        if (element.getDataPrecision() != null && column.getDataPrecision() != null) {
            anyChecked = true;
            if (!element.getDataPrecision().equals(column.getDataPrecision())) {
                issues.add(String.format("数据精度不符：标准 %d，实际 %d",
                        element.getDataPrecision(), column.getDataPrecision()));
            }
        }
        if (element.getDataScale() != null && column.getDataScale() != null) {
            anyChecked = true;
            if (!element.getDataScale().equals(column.getDataScale())) {
                issues.add(String.format("小数位数不符：标准 %d，实际 %d",
                        element.getDataScale(), column.getDataScale()));
            }
        }
        // 标准要求不可空而实际可空才算不符合；标准允许可空时不限制
        if (element.getNullableFlag() != null && element.getNullableFlag() == 0
                && column.getNullableFlag() != null) {
            anyChecked = true;
            if (column.getNullableFlag() == 1) {
                issues.add("可空性不符：标准要求不可空，实际可空");
            }
        }
        if (!anyChecked) {
            return new ComplianceResult(RESULT_SKIP, issues);
        }
        return new ComplianceResult(issues.isEmpty() ? RESULT_PASS : RESULT_FAIL, issues);
    }

}
