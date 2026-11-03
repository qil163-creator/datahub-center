package org.apache.dolphinscheduler.plugin.task.datax;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.*;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;

/** 保留官方 DATAX 插件，仅为已显式选择幂等写入的任务注册新类型。 */
public final class DatahubDataxUpsertTaskChannelFactory implements TaskChannelFactory {
    @Override public String getName() { return "DATAX_UPSERT"; }
    @Override public TaskChannel create() {
        return new TaskChannel() {
            @Override public AbstractTask createTask(TaskExecutionContext context) { return new DatahubDataxUpsertTask(context); }
            @Override public AbstractParameters parseParameters(String json) {
                return JSONUtils.parseObject(json, DatahubDataxUpsertParameters.class);
            }
        };
    }
}
