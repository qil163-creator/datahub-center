package cn.iocoder.yudao.framework.dbswitch.core.model;

/** 扩展目录属性的读取证据；仅返回数量，不把真实注释内容传给诊断调用方。 */
public record ExtendedPropertyDiagnostic(int columnsRead, boolean tableCommentPresent, int columnCommentsPresent) {

}
