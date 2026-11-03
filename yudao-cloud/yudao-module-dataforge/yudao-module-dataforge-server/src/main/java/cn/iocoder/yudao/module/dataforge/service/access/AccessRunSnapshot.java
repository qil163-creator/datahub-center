package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.seatunnel.NativeSeatunnelConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PREVIEW_PARAM_MISSING;

/** 不可变执行定义；SeaTunnel 原文只用于执行，不写入日志或普通执行记录响应。 */
public final class AccessRunSnapshot {

    /**
     * 限制数据同步运行快照工具的实例化，统一通过静态能力使用。
     */
    private AccessRunSnapshot() {
    }

    /**
     * 固定数据同步运行快照执行快照。
     *
     * @param definition 定义
     * @return 固定数据同步运行快照执行快照文本
     */
    public static String freeze(DataAccessDO definition) {
        JSONObject json = JSONUtil.parseObj(definition);
        JSONObject snapshot = new JSONObject();
        for (String name : List.of("id", "taskName", "orgId", "engineType", "sourceType", "sourceDatabaseId",
                "targetDatabaseId", "accessMode", "taskType", "cron", "dataAccessJson", "seatunnelConfig",
                "configFormat", "apiAccessJson")) {
            snapshot.set(name, json.get(name));
        }
        if (!Integer.valueOf(2).equals(definition.getEngineType()) && definition.getDataAccessJson() != null) {
            snapshot.set("dataAccessJson",
                    JSONUtil.parseObj(AccessTaskConfigCodec.write(definition.getDataAccessJson())));
        }
        if (Integer.valueOf(2).equals(definition.getEngineType())) {
            NativeSeatunnelConfig.validate(definition.getSeatunnelConfig(), definition.getConfigFormat());
            snapshot.set("configFormat", NativeSeatunnelConfig.normalizeFormat(definition.getConfigFormat()));
            // 原生作业不携带其他引擎遗留的平台绑定，原文和格式保持配对冻结。
            for (String field : List.of("sourceDatabaseId", "targetDatabaseId", "dataAccessJson", "apiAccessJson")) {
                snapshot.remove(field);
            }
        } else {
            // 切换引擎后的历史字段不参与执行，也不能把原始连接材料带入新快照。
            for (String field : List.of("seatunnelConfig", "configFormat")) {
                snapshot.remove(field);
            }
        }
        return snapshot.toString();
    }

    /**
     * 恢复数据同步运行快照。
     *
     * @param json JSON 内容
     * @return 数据同步任务
     */
    public static DataAccessDO restore(String json) {
        if (json == null || !JSONUtil.isTypeJSONObject(json)) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "运行快照");
        }
        JSONObject snapshot = JSONUtil.parseObj(json);
        JSONObject config = snapshot.getJSONObject("dataAccessJson");
        var definition = JSONUtil.toBean(snapshot, DataAccessDO.class);
        if (config != null) {
            definition.setDataAccessJson(AccessTaskConfigCodec.read(config.toString()));
        }
        return definition;
    }
}
