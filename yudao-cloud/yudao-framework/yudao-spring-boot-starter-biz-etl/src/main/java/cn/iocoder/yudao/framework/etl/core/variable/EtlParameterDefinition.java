package cn.iocoder.yudao.framework.etl.core.variable;

/** 与业务领域无关的参数定义，供 ETL 运行时适配器使用。 */
public record EtlParameterDefinition(String name, String type, Boolean required, String defaultValue,
                                     String min, String max, Integer minLength, Integer maxLength) {

}
