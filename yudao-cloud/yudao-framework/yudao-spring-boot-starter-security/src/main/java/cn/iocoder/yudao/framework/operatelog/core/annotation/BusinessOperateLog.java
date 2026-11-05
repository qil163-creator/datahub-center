package cn.iocoder.yudao.framework.operatelog.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将显式选定的业务操作汇总到系统操作日志，不采集请求体或响应中的业务数据。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BusinessOperateLog {

    /** 操作模块，由业务调用方提供可读名称。 */
    String type();

    /** 操作摘要，留空时使用接口的 OpenAPI 摘要。 */
    String action() default "";

    /**
     * 业务编号表达式，支持方法参数和解包后的 #result；集合逐个登记，空集合不登记。
     * 没有单一业务对象的操作可留空，使用 0 表示模块范围操作。
     */
    String bizId() default "";

    /** 返回 false 代表已受理的异步请求时，由调用方显式启用并使用受理动作摘要。 */
    boolean recordOnFalse() default false;
}
