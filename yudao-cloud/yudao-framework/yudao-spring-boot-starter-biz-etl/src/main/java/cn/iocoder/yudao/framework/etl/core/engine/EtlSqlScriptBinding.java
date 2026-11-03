package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;

import java.util.Objects;

/** 预检阶段冻结的数据源与脚本，授权规则由调用方负责。 */
public record EtlSqlScriptBinding(Long databaseId, SqlScriptPlan plan) {

    /**
     * 校验构造参数并初始化 {@code EtlSqlScriptBinding}。
     */
    public EtlSqlScriptBinding {
        Objects.requireNonNull(databaseId, "databaseId");
        Objects.requireNonNull(plan, "plan");
    }
}
