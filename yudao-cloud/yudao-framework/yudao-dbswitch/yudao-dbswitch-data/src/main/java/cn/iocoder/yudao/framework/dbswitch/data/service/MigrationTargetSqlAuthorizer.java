package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;

import java.util.List;

/** 由调用方授权组件实际生成的目标写入计划；实现须线程安全，拒绝时抛出异常。 */
@FunctionalInterface
public interface MigrationTargetSqlAuthorizer {

    /** 首次目标写入前调用；返回值必须保持对象、语句和参数顺序，运行时只执行返回的计划。 */
    ManagedSchemaScriptPlan authorize(String tableName, List<String> targetColumns, ManagedSchemaScriptPlan plan);
}
