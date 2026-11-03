package cn.iocoder.yudao.framework.quality.core.row;

/** 求值无法完成。这是执行错误，绝不表示数据质量通过或违规。 */
public final class QualityRowEvaluationException extends RuntimeException {

    private final String ruleCode;

    private final String errorCode;

    /**
     * 初始化 {@code QualityRowEvaluationException}及其父类状态。
     */
    public QualityRowEvaluationException(String ruleCode, String errorCode) {
        super("Quality rule evaluation failed: " + errorCode);
        this.ruleCode = ruleCode;
        this.errorCode = errorCode;
    }

    /**
     * 返回规则代码。
     */
    public String getRuleCode() {
        return ruleCode;
    }

    /**
     * 返回错误代码。
     */
    public String getErrorCode() {
        return errorCode;
    }
}
