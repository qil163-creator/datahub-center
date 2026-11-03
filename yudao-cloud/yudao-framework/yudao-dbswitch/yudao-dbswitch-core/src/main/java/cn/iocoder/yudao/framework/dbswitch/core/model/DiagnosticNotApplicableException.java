package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.sql.SQLFeatureNotSupportedException;

/** 产品原生语义不适用某项诊断；与诊断代码尚未实现严格区分，不代表检查通过。 */
public final class DiagnosticNotApplicableException extends SQLFeatureNotSupportedException {

    public DiagnosticNotApplicableException(String explanation) {
        super(explanation, "0A000");
    }
}
