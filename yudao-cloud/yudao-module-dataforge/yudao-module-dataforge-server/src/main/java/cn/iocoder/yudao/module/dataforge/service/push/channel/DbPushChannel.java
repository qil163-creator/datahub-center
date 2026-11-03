package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushChannel;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushReadOnlySupport;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TARGET_DATABASE_NOT_EXISTS;

/**
 * 数据库通道：整表/SQL 走 dbswitch，不按批次 HTTP 式推送
 *
 * @author Li
 */
@Component
public class DbPushChannel implements DataPushChannel {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    /**
     * 返回当前实现对应的类型标识。
     *
     * @return 类型
     */
    @Override
    public Integer getType() {
        return PushChannelTypeEnum.DB.getType();
    }

    /**
     * 推送数据库推送通道。
     *
     * @param payload 载荷
     * @param channelJson 通道JSON
     * @param log 日志
     */
    @Override
    public void push(PushPayload payload, String channelJson, StringBuilder log) {
        throw new UnsupportedOperationException("数据库通道请使用 syncFromSource");
    }

    /**
     * 获取数据库推送通道的同步来源来源。
     *
     * @param source 来源数据
     * @param task 任务记录
     * @param channelJson 通道JSON
     * @param startVal 开始值
     * @param endVal 结束值
     * @param log 日志
     * @param abortHook 中止钩子
     * @return 同步来源来源数值
     */
    public long syncFromSource(AccessDatabaseResolver.ConnectionInfo source,
            DataPushTaskDO task,
            String channelJson,
            String startVal,
            String endVal,
            StringBuilder log,
            Consumer<Runnable> abortHook) throws Exception {
        // 此通道没有强类型检查点与提交回执绑定。引擎契约变化后，绝不能将旧版
        // 字符串水位请求转为无边界追加。
        if (cn.iocoder.yudao.module.dataforge.enums.push.PushModeEnum.isIncrement(task.getPushMode())
                || startVal != null || endVal != null) {
            throw new IllegalArgumentException("数据库通道的旧字符串水位增量已停用，请使用数据接入的强类型游标任务");
        }
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        DataPushChannelConfigVO.DbConfig db = config == null ? null : config.getDb();
        if (db == null || db.getDatabaseId() == null) {
            throw new IllegalStateException("未配置目标数据源");
        }
        AccessDatabaseResolver.ConnectionInfo target = databaseResolver.resolve(
                db.getDatabaseId(), DATA_PUSH_TARGET_DATABASE_NOT_EXISTS);

        String sourceTable = StrUtil.blankToDefault(task.getTableName(), "custom_sql");
        String targetTable = StrUtil.blankToDefault(db.getTargetTableName(),
                StrUtil.blankToDefault(task.getObjectName(), sourceTable));

        MigrationRequest properties = new MigrationRequest();
        SourceDataSourceProperties sourceProps = new SourceDataSourceProperties();
        boolean sqlSource = PushTypeEnum.SQL.getType().equals(task.getPushType());
        sourceProps.setSourceType(sqlSource ? SourceType.SQL.getCode() : SourceType.TABLE.getCode());
        sourceProps.setSourceProductType(source.getProductType());
        sourceProps.setUrl(DataPushReadOnlySupport.applyUrl(source.getProductType(), source.getJdbcUrl()));
        sourceProps.setDriverClassName(source.getProductType().getDriveClassName());
        sourceProps.setUsername(source.getUsername());
        sourceProps.setPassword(source.getPassword());
        sourceProps.setSourceSchema(StrUtil.blankToDefault(db.getSourceSchema(), source.getSchema()));
        sourceProps.setFetchSize(task.getBatchSize() == null || task.getBatchSize() <= 0
                ? 100 : task.getBatchSize());
        if (sqlSource) {
            sourceProps.setSourceSql(task.getSqlText());
        } else {
            sourceProps.setIncludeOrExclude(1);
            sourceProps.setSourceIncludes(task.getTableName());
            if (StrUtil.isNotBlank(targetTable) && !targetTable.equals(task.getTableName())) {
                List<PatternMapper> mappers = new ArrayList<>();
                mappers.add(new PatternMapper("^" + Pattern.quote(task.getTableName()) + "$", targetTable));
                sourceProps.setRegexTableMapper(mappers);
            }
        }
        properties.getSource().add(sourceProps);

        TargetDataSourceProperties targetProps = properties.getTarget();
        targetProps.setTargetProductType(target.getProductType());
        targetProps.setUrl(target.getJdbcUrl());
        targetProps.setDriverClassName(target.getProductType().getDriveClassName());
        targetProps.setUsername(target.getUsername());
        targetProps.setPassword(target.getPassword());
        targetProps.setTargetSchema(StrUtil.blankToDefault(db.getTargetSchema(), target.getSchema()));
        targetProps.setTargetTable(targetTable);
        targetProps.setWriteMode(db.getWriteMode());
        if (cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.isExplicit(db.getWriteMode())) {
            targetProps.setTargetDrop(false);
            targetProps.setTargetTruncateData(false);
            targetProps.setFailOnFieldReadError(
                    cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.STRICT_COPY.equals(
                            db.getWriteMode()));
        } else {
            // 增量推送禁止删表重建，否则第二次只会写入增量区间，历史数据会丢
            targetProps.setTargetDrop(StrUtil.isBlank(task.getIncrementColumn())
                    && Boolean.TRUE.equals(db.getTargetDrop()));
        }
        targetProps.setSyncExist(db.getSyncExist() == null || db.getSyncExist());
        targetProps.setChangeDataSync(Boolean.TRUE.equals(db.getChangeDataSync()));

        StringBuilder tableError = new StringBuilder();
        MigrationService migrationService = new MigrationService(properties, tableResult -> {
            if (StrUtil.isNotBlank(tableResult.getErrorMsg())) {
                tableError.append(tableResult.getErrorMsg());
            }
        }, null, dbSwitchRuntime);
        if (abortHook != null) {
            abortHook.accept(migrationService::interrupt);
        }
        DbSwitchResult result = migrationService.run();
        // 表处理成功不等于外部写入/连接终止已确认；未知结果不能让调用方推进增量水位。
        if (result.getTermination() != MigrationTermination.CONFIRMED) {
            throw new UnconfirmedWriteException();
        }
        if (log != null) {
            log.append("DB targetId=").append(db.getDatabaseId())
                    .append(" table=").append(targetTable)
                    .append(" rows=").append(result.getTotalRowCount().get())
                    .append('\n');
        }
        if (!result.getIfAllSuccess().get()) {
            throw new IllegalStateException(StrUtil.blankToDefault(tableError.toString(), "写入目标库失败"));
        }
        return result.getTotalRowCount().get();
    }

}
