package cn.iocoder.yudao.framework.etl.core.executor;

import cn.iocoder.yudao.framework.etl.core.executor.builtin.*;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * ETL 节点执行器注册表：toolCode → 执行器工厂；业务侧可注册扩展节点
 *
 * @author Li
 */
public class EtlExecutorRegistry {

    private final Map<String, BiFunction<EtlNodeDef, EtlRunContext, AbstractEtlNodeExecutor>> factories =
            new ConcurrentHashMap<>();

    /**
     * 初始化 {@code EtlExecutorRegistry}。
     */
    public EtlExecutorRegistry() {
        // 输入
        register(ReadDbExecutor.TOOL_CODE, ReadDbExecutor::new);
        register(ReadKafkaExecutor.TOOL_CODE, ReadKafkaExecutor::new);
        register(RestApiExecutor.TOOL_CODE, RestApiExecutor::new);
        register(ReadExcelExecutor.TOOL_CODE, ReadExcelExecutor::new);
        register(ReadCsvExecutor.TOOL_CODE, ReadCsvExecutor::new);
        register(ReadJsonExecutor.TOOL_CODE, ReadJsonExecutor::new);
        register("read-gzip-csv", ReadCsvExecutor::new);
        // 输出
        register(InsertDbExecutor.TOOL_CODE, InsertDbExecutor::new);
        register(ManagedDbOutputExecutor.TOOL_CODE, ManagedDbOutputExecutor::new);
        register(WriteKafkaExecutor.TOOL_CODE, WriteKafkaExecutor::new);
        register(WriteExcelExecutor.TOOL_CODE, WriteExcelExecutor::new);
        register(WriteCsvExecutor.TOOL_CODE, WriteCsvExecutor::new);
        register(WriteJsonExecutor.TOOL_CODE, WriteJsonExecutor::new);
        register(WriteTextExecutor.TOOL_CODE, WriteTextExecutor::new);
        // 转换
        register("boundary-input", SubflowBoundaryExecutor::new);
        register("boundary-output", SubflowBoundaryExecutor::new);
        register("boundary-bridge", SubflowBoundaryExecutor::new);
        register(ColumnMapExecutor.TOOL_CODE, ColumnMapExecutor::new);
        register(DataFilterExecutor.TOOL_CODE, DataFilterExecutor::new);
        register(DataCleanExecutor.TOOL_CODE, DataCleanExecutor::new);
        register(DataEncryptExecutor.TOOL_CODE, DataEncryptExecutor::new);
        register(DataDecryptExecutor.TOOL_CODE, DataDecryptExecutor::new);
        register(DataMergeExecutor.TOOL_CODE, DataMergeExecutor::new);
        register(DataDesensitizeExecutor.TOOL_CODE, DataDesensitizeExecutor::new);
        register(DataConditionExecutor.TOOL_CODE, DataConditionExecutor::new);
        register(FieldDeriveExecutor.TOOL_CODE, FieldDeriveExecutor::new);
        register(EtlFieldRules.STRING_OPERATION, FieldRulesExecutor::new);
        register(EtlFieldRules.STRING_REPLACE, FieldRulesExecutor::new);
        register(EtlFieldRules.SET_FIELD_VALUE, FieldRulesExecutor::new);
        register(AddConstantExecutor.TOOL_CODE, AddConstantExecutor::new);
        register(RowColumnExecutor.TOOL_CODE, RowColumnExecutor::new);
        register(SortRecordsExecutor.TOOL_CODE, SortRecordsExecutor::new);
        register(FieldSplitExecutor.TOOL_CODE, FieldSplitExecutor::new);
        register(IdGeneratorExecutor.TOOL_CODE, IdGeneratorExecutor::new);
        register(CalculatorExecutor.TOOL_CODE, CalculatorExecutor::new);
        register(ValueMapExecutor.TOOL_CODE, ValueMapExecutor::new);
        register(QualityCheckExecutor.TOOL_CODE, QualityCheckExecutor::new);
        register(NumberRangeExecutor.TOOL_CODE, NumberRangeExecutor::new);
        register(SplitToRowsExecutor.TOOL_CODE, SplitToRowsExecutor::new);
        register(FlattenRowExecutor.TOOL_CODE, FlattenRowExecutor::new);
        register(SortMergeExecutor.TOOL_CODE, SortMergeExecutor::new);
        register(MultiJoinExecutor.TOOL_CODE, MultiJoinExecutor::new);
        register(CartesianProductExecutor.TOOL_CODE, CartesianProductExecutor::new);
        register(GenerateRecordsExecutor.TOOL_CODE, GenerateRecordsExecutor::new);
        register(RandomRecordsExecutor.TOOL_CODE, RandomRecordsExecutor::new);
        // 脚本
        register(ShellScriptExecutor.TOOL_CODE, ShellScriptExecutor::new);
        register(PythonScriptExecutor.PY_TOOL_CODE, PythonScriptExecutor::new);
        register(SqlScriptExecutor.TOOL_CODE, SqlScriptExecutor::new);
        register(JavaScriptExecutor.TOOL_CODE, JavaScriptExecutor::new);
        register(JsScriptExecutor.TOOL_CODE, JsScriptExecutor::new);
    }

    /**
     * 登记受管资源的停止回调及实际退出检查。
     */
    public void register(String toolCode,
            BiFunction<EtlNodeDef, EtlRunContext, AbstractEtlNodeExecutor> factory) {
        factories.put(toolCode, factory);
    }

    /**
     * 返回当前支持。
     */
    public boolean supports(String toolCode) {
        return factories.containsKey(toolCode);
    }

    /**
     * 根据给定参数创建 {@code AbstractEtlNodeExecutor} 实例。
     */
    public AbstractEtlNodeExecutor create(EtlNodeDef node, EtlRunContext context) {
        BiFunction<EtlNodeDef, EtlRunContext, AbstractEtlNodeExecutor> factory = factories.get(node.getToolCode());
        if (factory == null) {
            throw new IllegalStateException(
                    String.format("暂不支持的节点类型【%s】", node.getToolCode()));
        }
        return factory.apply(node, context);
    }

}
