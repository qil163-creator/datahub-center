package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_ISSUE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_ISSUE_ACCESS_FORBIDDEN;

/**
 * 数据治理-数据质量-问题数据 Service 实现
 *
 * 定位式留存：平台只留「定位SQL + 主键值」，查看明细时实时回源查询
 *
 * @author Li
 */
@Service
@Validated
public class QualityIssueDataServiceImpl implements QualityIssueDataService {

    @Resource
    private DatabaseApi databaseApi;

    private static final int DETAIL_MAX_LIMIT = 1000;

    @Resource
    private QualityIssueDataMapper issueDataMapper;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityRuleMapper ruleMapper;

    /**
     * 获得问题数据。
     */
    @Override
    public QualityIssueDataDO getIssueData(Long id) {
        return issueDataMapper.selectById(id);
    }

    /**
     * 获得问题数据分页。
     */
    @Override
    public PageResult<QualityIssueDataRespVO> getIssueDataPage(QualityIssueDataPageReqVO pageReqVO) {
        PageResult<QualityIssueDataDO> pageResult = issueDataMapper.selectPage(pageReqVO);
        PageResult<QualityIssueDataRespVO> result = BeanUtils.toBean(pageResult, QualityIssueDataRespVO.class);
        fillNames(result.getList());
        return result;
    }

    /**
     * 更新处理状态
     */
    @Override
    public void updateIssueStatus(Long id, String issueStatus) {
        validateExists(id);
        issueDataMapper.updateById(QualityIssueDataDO.builder().id(id).issueStatus(issueStatus).build());
    }

    /**
     * 删除问题数据。
     */
    @Override
    public void deleteIssueData(Long id) {
        validateExists(id);
        issueDataMapper.deleteById(id);
    }

    /**
     * 查看问题明细：按定位 SQL 实时回源查询（源库已修复的行不会再出现）
     *
     *  @return 问题行明细（每行为「列名 → 值」）
     */
    @Override
    public List<Map<String, Object>> getIssueDetail(Long id, Integer limit) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        QualityIssueDataDO issue = validateExists(id);
        if (StrUtil.isBlank(issue.getLocationSql()) || issue.getObjectId() == null) {
            return List.of();
        }
        QualityCheckObjectDO object = checkObjectMapper.selectById(issue.getObjectId());
        if (object == null) {
            throw exception(QUALITY_CHECK_OBJECT_NOT_EXISTS);
        }
        DatabaseQueryReqDTO request = new DatabaseQueryReqDTO();
        request.setDatabaseId(object.getDatasourceConnId());
        request.setSql(issue.getLocationSql());
        request.setLimit(limit == null ? 100 : Math.max(1, Math.min(limit, DETAIL_MAX_LIMIT)));
        // 复用受管只读查询入口，保留问题对象访问校验和受限 SELECT 技术约束。
        // 不支持的定位 SQL 明确拒绝，不能回退到携带数据源凭据的裸 JDBC 查询。
        var response = databaseApi.executeQuery(request).getCheckedData();
        if (response == null || response.getRows() == null) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        return response.getRows().stream().map(values -> {
            Map<String, Object> row = new LinkedHashMap<>();
            values.forEach((name, value) -> row.put(name, value == null ? null : String.valueOf(value)));
            return row;
        }).toList();
    }

    /**
     * 补充质量问题数据的名称。
     */
    private void fillNames(List<QualityIssueDataRespVO> list) {
        List<Long> taskIds = list.stream().map(QualityIssueDataRespVO::getTaskId)
                .filter(Objects::nonNull).distinct().toList();
        if (!taskIds.isEmpty()) {
            Map<Long, String> taskNames = taskMapper.selectBatchIds(taskIds).stream()
                    .collect(Collectors.toMap(QualityTaskDO::getId, QualityTaskDO::getTaskName));
            list.forEach(vo -> vo.setTaskName(taskNames.get(vo.getTaskId())));
        }
        List<Long> ruleIds = list.stream().map(QualityIssueDataRespVO::getRuleId)
                .filter(Objects::nonNull).distinct().toList();
        if (!ruleIds.isEmpty()) {
            Map<Long, String> ruleNames = ruleMapper.selectBatchIds(ruleIds).stream()
                    .collect(Collectors.toMap(QualityRuleDO::getId, QualityRuleDO::getRuleName));
            list.forEach(vo -> vo.setRuleName(ruleNames.get(vo.getRuleId())));
        }
    }

    /**
     * 读取并校验质量问题数据记录存在，不存在时抛出业务异常。
     */
    private QualityIssueDataDO validateExists(Long id) {
        QualityIssueDataDO issue = id == null ? null : issueDataMapper.selectById(id);
        if (issue == null) {
            throw exception(QUALITY_ISSUE_NOT_EXISTS);
        }
        return issue;
    }

}
