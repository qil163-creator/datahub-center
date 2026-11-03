package cn.iocoder.yudao.module.dataforge.service.dashboard;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.dashboard.vo.DataforgeDashboardRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessReleaseStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessRunStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 首页看板（数据集成 / 数据研发） Service 实现类：
 * 只做数量级聚合，供工作台“数据全链路总览 / 今日运行概况”和数据总览页使用。
 *
 * @author Li
 */
@Service
public class DataforgeDashboardServiceImpl implements DataforgeDashboardService {

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DatabaseTypeMapper databaseTypeMapper;

    @Resource
    private DataAccessMapper dataAccessMapper;

    @Resource
    private DataAccessTaskMapper dataAccessTaskMapper;

    @Resource
    private DevTaskMapper devTaskMapper;

    @Resource
    private DevRecordMapper devRecordMapper;

    @Resource
    private DataPushTaskMapper dataPushTaskMapper;

    @Resource
    private DataPushTaskLogMapper dataPushTaskLogMapper;

    /**
     * 获得首页看板汇总：数据源 / 数据同步任务 / 开发任务数量，以及今日执行情况
     *
     * @return 汇总
     */
    @Override
    public DataforgeDashboardRespVO getSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        DataforgeDashboardRespVO vo = new DataforgeDashboardRespVO();
        // 1. 数据源
        vo.setDatabaseCount(databaseMapper.selectCount());
        vo.setDatabaseTypeDistribution(buildDatabaseTypeDistribution());
        // 2. 数据集成
        vo.setAccessJobCount(dataAccessMapper.selectCount());
        vo.setAccessReleasedCount(dataAccessMapper.selectCount(
                DataAccessDO::getStatus, AccessReleaseStatusEnum.RELEASED.getStatus()));
        vo.setTodayAccessRun(buildTodayAccessRun(todayStart));
        vo.setPushJobCount(dataPushTaskMapper.selectCount());
        vo.setPushReleasedCount(dataPushTaskMapper.selectCount(
                DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_RELEASED));
        vo.setTodayPushRun(buildTodayPushRun(todayStart));
        // 3. 数据开发
        vo.setDevTaskCount(devTaskMapper.selectCount());
        vo.setDevTaskOnlineCount(devTaskMapper.selectCount(DevTaskDO::getStatus, DevTaskDO.STATUS_ONLINE));
        vo.setTodayDevRun(buildTodayDevRun(todayStart));
        return vo;
    }

    /** 数据源类型分布：数据源数量有限，直接按类型编号分组后关联类型名称 */
    private List<DataforgeDashboardRespVO.NameCount> buildDatabaseTypeDistribution() {
        List<DatabaseDO> databases = databaseMapper.selectList(new LambdaQueryWrapperX<DatabaseDO>()
                .select(DatabaseDO::getDatabaseType));
        Map<Long, DatabaseTypeDO> typeMap = convertMap(databaseTypeMapper.selectList(), DatabaseTypeDO::getId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DatabaseDO database : databases) {
            DatabaseTypeDO type = database.getDatabaseType() == null ? null
                    : typeMap.get(database.getDatabaseType().longValue());
            String name = type != null && type.getTypeName() != null ? type.getTypeName() : "未知类型";
            counts.merge(name, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(entry -> {
                    DataforgeDashboardRespVO.NameCount item = new DataforgeDashboardRespVO.NameCount();
                    item.setName(entry.getKey());
                    item.setCount(entry.getValue());
                    return item;
                })
                .toList();
    }

    /** 今日数据同步执行：按运行状态归并为 成功 / 失败 / 运行中（含等待中） */
    private DataforgeDashboardRespVO.RunSummary buildTodayAccessRun(LocalDateTime todayStart) {
        List<DataAccessTaskDO> tasks = dataAccessTaskMapper.selectList(new LambdaQueryWrapperX<DataAccessTaskDO>()
                .select(DataAccessTaskDO::getRunStatus)
                .ge(DataAccessTaskDO::getCreateTime, todayStart));
        return summarize(tasks, DataAccessTaskDO::getRunStatus,
                AccessRunStatusEnum.SUCCESS.getStatus(),
                AccessRunStatusEnum.FAILED.getStatus(),
                List.of(AccessRunStatusEnum.RUNNING.getStatus(), AccessRunStatusEnum.WAITING.getStatus()));
    }

    /** 今日数据推送执行：成功 / 失败 / 运行中（含等待中） */
    private DataforgeDashboardRespVO.RunSummary buildTodayPushRun(LocalDateTime todayStart) {
        List<DataPushTaskLogDO> logs = dataPushTaskLogMapper.selectList(new LambdaQueryWrapperX<DataPushTaskLogDO>()
                .select(DataPushTaskLogDO::getRunStatus)
                .ge(DataPushTaskLogDO::getStartTime, todayStart));
        return summarize(logs, DataPushTaskLogDO::getRunStatus,
                PushRunStatusEnum.SUCCESS.getStatus(),
                PushRunStatusEnum.FAILED.getStatus(),
                List.of(PushRunStatusEnum.RUNNING.getStatus(), PushRunStatusEnum.WAITING.getStatus()));
    }

    /** 今日开发任务执行：SUCCESS / FAILED / RUNNING（STOPPED 只计入总数） */
    private DataforgeDashboardRespVO.RunSummary buildTodayDevRun(LocalDateTime todayStart) {
        List<DevRecordDO> records = devRecordMapper.selectList(new LambdaQueryWrapperX<DevRecordDO>()
                .select(DevRecordDO::getStatus)
                .ge(DevRecordDO::getCreateTime, todayStart));
        return summarize(records, DevRecordDO::getStatus,
                DevRecordDO.STATUS_SUCCESS, DevRecordDO.STATUS_FAILED, List.of(DevRecordDO.STATUS_RUNNING));
    }

    /**
     * 获取数据开发仪表盘的汇总。
     *
     * @param <T> 处理数据的类型
     * @param <S> 处理数据的类型
     * @param records 记录
     * @param statusGetter 状态读取方法
     * @param successStatus 成功状态
     * @param failedStatus 失败状态
     * @param runningStatuses 运行中状态集合
     * @return 运行汇总
     */
    private <T, S> DataforgeDashboardRespVO.RunSummary summarize(List<T> records, Function<T, S> statusGetter,
            S successStatus, S failedStatus,
            List<S> runningStatuses) {
        DataforgeDashboardRespVO.RunSummary summary = new DataforgeDashboardRespVO.RunSummary();
        long success = 0;
        long failed = 0;
        long running = 0;
        for (T record : records) {
            S status = statusGetter.apply(record);
            if (Objects.equals(status, successStatus)) {
                success++;
            } else if (Objects.equals(status, failedStatus)) {
                failed++;
            } else if (runningStatuses.contains(status)) {
                running++;
            }
        }
        summary.setTotalCount((long) records.size());
        summary.setSuccessCount(success);
        summary.setFailedCount(failed);
        summary.setRunningCount(running);
        return summary;
    }

}
