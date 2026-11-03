package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstancePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_INSTANCE_NOT_EXISTS;

/**
 * 数据治理-元数据采集实例 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class MetaCollectInstanceServiceImpl implements MetaCollectInstanceService {

    @Resource
    private MetaCollectInstanceMapper instanceMapper;

    @Resource
    private MetaCollectLogMapper logMapper;

    /**
     * 采集实例分页
     */
    @Override
    public PageResult<MetaCollectInstanceDO> getInstancePage(MetaCollectInstancePageReqVO pageReqVO) {
        return instanceMapper.selectPage(pageReqVO);
    }

    /**
     * 获取采集实例
     */
    @Override
    public MetaCollectInstanceDO getInstance(Long id) {
        MetaCollectInstanceDO instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw exception(META_COLLECT_INSTANCE_NOT_EXISTS);
        }
        return instance;
    }

    /**
     * 获取采集实例日志（按时间升序）
     */
    @Override
    public List<MetaCollectLogDO> getInstanceLogList(Long instanceId) {
        getInstance(instanceId);
        return logMapper.selectListByInstanceId(instanceId);
    }

    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactions;

    /**
     * 删除实例。
     */
    @Override
    public void deleteInstances(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_SCOPE_INVALID);
        }
        transactions.executeWithoutResult(tx -> {
            for (Long id : ids.stream().distinct().sorted().toList()) {
                var instance = instanceMapper.lockInstance(id);
                if (instance == null) {
                    throw exception(META_COLLECT_INSTANCE_NOT_EXISTS);
                }
                if (instance.getStatus() == 0 || instance.getStatus() == 1) {
                    throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_ACTIVE);
                }
                logMapper.delete(
                        new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectLogDO>().eq(
                                MetaCollectLogDO::getInstanceId, id));
                instanceMapper.deleteById(id);
            }
        });
    }

    /**
     * 向指定元数据采集实例追加运行日志。
     */
    @Override
    public void writeLog(Long id, java.io.OutputStream output) throws java.io.IOException {
        getInstance(id);
        long cursor = 0;
        long bytes = 0;
        while (true) {
            var batch = logMapper.selectList(
                    new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<MetaCollectLogDO>()
                            .eq(MetaCollectLogDO::getInstanceId, id).gt(MetaCollectLogDO::getId, cursor)
                            .orderByAsc(MetaCollectLogDO::getId).last("LIMIT 500"));
            if (batch.isEmpty()) {
                return;
            }
            for (var row : batch) {
                String line = String.format("%s [%s] [%s] %s %s%n", row.getLogTime(), row.getLogLevel(), row.getStage(),
                        java.util.Objects.toString(row.getObjectRef(), ""),
                        java.util.Objects.toString(row.getContent(), ""));
                byte[] value = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes + value.length > 16L * 1024 * 1024) {
                    output.write("日志超过16MiB，本次下载已截断。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return;
                }
                output.write(value);
                bytes += value.length;
                cursor = row.getId();
            }
        }
    }

}
