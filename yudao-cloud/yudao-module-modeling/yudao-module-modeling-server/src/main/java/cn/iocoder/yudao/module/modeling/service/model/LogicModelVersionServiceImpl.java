package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionDetailRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelVersionDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelVersionMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import tools.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 逻辑模型版本实现。版本写入只发生在发布事务内，读取与比对不持锁。
 */
@Slf4j
@Service
@Validated
public class LogicModelVersionServiceImpl implements LogicModelVersionService {

    private static final String CURRENT_LABEL = "当前设计";

    @Resource
    private LogicModelVersionMapper versionMapper;

    @Resource
    private LogicModelMapper modelMapper;

    @Resource
    private LogicModelColumnService columnService;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    public LogicModelVersionDO recordPublish(LogicModelDO published, List<ModelFieldItem> fields, String typeConfigJson,
            Long publishedBy, LocalDateTime publishedTime) {
        List<ModelFieldItem> safeFields = fields == null ? List.of() : fields;
        LogicModelVersionDO previous = versionMapper.selectLatest(published.getId());
        Map<String, Object> header = LogicModelVersionService.headerSnapshot(published);
        Map<String, Object> summary;
        if (previous == null) {
            summary = Map.of("initial", true);
        } else {
            summary = ModelFieldDiff.summaryOf(ModelFieldDiff.compare(header(previous), fields(previous), header,
                    safeFields, ModelFieldDiff.FIELD_ATTRIBUTES));
        }
        LogicModelVersionDO version = LogicModelVersionDO.builder()
                .modelId(published.getId())
                .versionNo(previous == null ? 1 : previous.getVersionNo() + 1)
                .modelRevision(published.getRevision() == null ? 0L : published.getRevision())
                .modelSnapshot(JsonUtils.toJsonString(header))
                .fieldsSnapshot(JsonUtils.toJsonString(safeFields))
                .typeConfig(StrUtil.isBlank(typeConfigJson) ? null : typeConfigJson)
                .fieldCount(safeFields.size())
                .changeSummary(JsonUtils.toJsonString(summary))
                .publishedBy(publishedBy)
                .publishedTime(publishedTime == null ? LocalDateTime.now() : publishedTime)
                .build();
        versionMapper.insert(version);
        return version;
    }

    @Override
    public List<LogicModelVersionRespVO> listVersions(Long modelId) {
        List<LogicModelVersionDO> rows = versionMapper.selectListByModelId(modelId);
        Map<Long, AdminUserRespDTO> users = users(rows.stream().map(LogicModelVersionDO::getPublishedBy).toList());
        List<LogicModelVersionRespVO> result = new ArrayList<>();
        for (LogicModelVersionDO row : rows) {
            LogicModelVersionRespVO item = new LogicModelVersionRespVO();
            fill(item, row, users);
            result.add(item);
        }
        return result;
    }

    @Override
    public LogicModelVersionDetailRespVO getVersion(Long id) {
        LogicModelVersionDO row = requireVersion(id);
        LogicModelVersionDetailRespVO result = new LogicModelVersionDetailRespVO();
        fill(result, row, users(Collections.singletonList(row.getPublishedBy())));
        result.setModelSnapshot(header(row));
        result.setFields(fields(row));
        result.setTypeConfig(StrUtil.isBlank(row.getTypeConfig()) ? null
                : JsonUtils.parseObject(row.getTypeConfig(), Object.class));
        return result;
    }

    @Override
    public ModelDiffRespVO compare(Long modelId, Long fromVersionId, Long toVersionId) {
        LogicModelVersionDO from = requireVersion(fromVersionId);
        if (!Objects.equals(from.getModelId(), modelId)) {
            throw exception(MODEL_VERSION_MISMATCH);
        }
        ModelDiffRespVO result;
        String toLabel;
        if (toVersionId == null) {
            LogicModelDO current = modelMapper.selectById(modelId);
            if (current == null) {
                throw exception(LOGIC_MODEL_NOT_EXISTS);
            }
            result = ModelFieldDiff.compare(header(from), fields(from), LogicModelVersionService.headerSnapshot(current),
                    columnService.listByModelId(modelId), ModelFieldDiff.FIELD_ATTRIBUTES);
            toLabel = CURRENT_LABEL;
        } else {
            LogicModelVersionDO to = requireVersion(toVersionId);
            if (!Objects.equals(to.getModelId(), modelId)) {
                throw exception(MODEL_VERSION_MISMATCH);
            }
            result = ModelFieldDiff.compare(header(from), fields(from), header(to), fields(to),
                    ModelFieldDiff.FIELD_ATTRIBUTES);
            toLabel = label(to);
        }
        result.setFromLabel(label(from));
        result.setToLabel(toLabel);
        return result;
    }

    @Override
    public void deleteByModelId(Long modelId) {
        versionMapper.deleteByModelId(modelId);
    }

    private LogicModelVersionDO requireVersion(Long id) {
        LogicModelVersionDO row = id == null ? null : versionMapper.selectById(id);
        if (row == null) {
            throw exception(MODEL_VERSION_NOT_EXISTS);
        }
        return row;
    }

    private void fill(LogicModelVersionRespVO item, LogicModelVersionDO row, Map<Long, AdminUserRespDTO> users) {
        item.setId(row.getId());
        item.setModelId(row.getModelId());
        item.setVersionNo(row.getVersionNo());
        item.setVersionLabel(label(row));
        item.setModelRevision(row.getModelRevision());
        item.setFieldCount(row.getFieldCount());
        item.setChangeSummary(StrUtil.isBlank(row.getChangeSummary()) ? Map.of()
                : JsonUtils.parseObject(row.getChangeSummary(), new TypeReference<Map<String, Object>>() {
                }));
        item.setPublishedBy(row.getPublishedBy());
        AdminUserRespDTO user = row.getPublishedBy() == null ? null : users.get(row.getPublishedBy());
        item.setPublishedByName(user == null ? null : user.getNickname());
        item.setPublishedTime(row.getPublishedTime());
    }

    private static String label(LogicModelVersionDO row) {
        return "V" + row.getVersionNo();
    }

    private static Map<String, Object> header(LogicModelVersionDO row) {
        if (StrUtil.isBlank(row.getModelSnapshot())) {
            return Map.of();
        }
        return JsonUtils.parseObject(row.getModelSnapshot(), new TypeReference<Map<String, Object>>() {
        });
    }

    private static List<ModelFieldItem> fields(LogicModelVersionDO row) {
        if (StrUtil.isBlank(row.getFieldsSnapshot())) {
            return List.of();
        }
        return JsonUtils.parseArray(row.getFieldsSnapshot(), ModelFieldItem.class);
    }

    /** 用户服务不可用时只展示编号，不影响版本读取。 */
    private Map<Long, AdminUserRespDTO> users(Collection<Long> ids) {
        Set<Long> distinct = new HashSet<>();
        ids.forEach(id -> {
            if (id != null) {
                distinct.add(id);
            }
        });
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            return adminUserApi.getUserMap(distinct);
        } catch (RuntimeException unavailable) {
            log.warn("[users][发布人名称暂不可用 count={}]", distinct.size());
            return Map.of();
        }
    }
}
