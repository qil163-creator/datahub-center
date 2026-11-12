package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskPreviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskPreviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskSceneRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskSceneUpdateReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyMaskSceneDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyMaskSceneMapper;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskEngine;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyFieldMaskService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.SAFETY_MASK_INVALID;

/**
 * 动态脱敏的管理端操作：场景开关与策略预览。
 * <p>
 * 数据范围：场景开关是平台级安全策略，按权限码控制，平台内全可见；策略预览只读取元数据字段与分类，
 * 不读取业务数据，演示效果只作用于管理员输入的示例值。
 */
@Service
public class SafetyMaskSceneService {

    @Resource
    private SafetyMaskSceneMapper sceneMapper;
    @Resource
    private SafetyDynamicMaskService dynamicMaskService;
    @Resource
    private SafetyFieldMaskService fieldMaskService;
    @Resource
    private MetaTableKeyResolver tableKeyResolver;
    @Resource
    private MetaColumnMapper columnMapper;

    /**
     * 列出全部场景；未配置的场景按启用展示。
     */
    public List<SafetyMaskSceneRespVO> listScenes() {
        Map<String, SafetyMaskSceneDO> rows = new HashMap<>();
        sceneMapper.selectList().forEach(row -> rows.put(row.getSceneCode(), row));
        List<SafetyMaskSceneRespVO> result = new ArrayList<>();
        for (SafetyMaskSceneEnum scene : SafetyMaskSceneEnum.values()) {
            SafetyMaskSceneDO row = rows.get(scene.name());
            SafetyMaskSceneRespVO vo = new SafetyMaskSceneRespVO();
            vo.setSceneCode(scene.name());
            vo.setSceneName(scene.getLabel());
            vo.setWhitelistScene(scene.getWhitelistScene());
            vo.setEnabled(row == null || !Boolean.FALSE.equals(row.getEnabled()));
            if (row != null) {
                vo.setId(row.getId());
                vo.setRemark(row.getRemark());
                vo.setRevision(row.getRevision());
                vo.setUpdateTime(row.getUpdateTime());
            } else {
                vo.setRevision(0);
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 更新场景开关；关闭时必须说明原因。按修订号 CAS，避免覆盖他人修改。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateScene(SafetyMaskSceneUpdateReqVO req) {
        if (Boolean.FALSE.equals(req.getEnabled()) && StrUtil.isBlank(req.getRemark())) {
            throw exception(SAFETY_MASK_INVALID, "关闭动态脱敏必须填写原因");
        }
        SafetyMaskSceneDO row = sceneMapper.selectByScene(req.getSceneCode());
        if (row == null) {
            if (req.getExpectedRevision() != 0) {
                throw exception(SAFETY_MASK_INVALID, "场景已被其他操作修改，请刷新后重试");
            }
            SafetyMaskSceneDO created = new SafetyMaskSceneDO();
            created.setSceneCode(req.getSceneCode());
            created.setEnabled(req.getEnabled());
            created.setRemark(StrUtil.trimToNull(req.getRemark()));
            created.setRevision(1);
            sceneMapper.insert(created);
            return;
        }
        if (sceneMapper.updateByRevision(row.getId(), req.getExpectedRevision(), req.getEnabled(),
                StrUtil.trimToNull(req.getRemark())) != 1) {
            throw exception(SAFETY_MASK_INVALID, "场景已被其他操作修改，请刷新后重试");
        }
    }

    /**
     * 预览某用户在某场景下读取一张表时各列的处理方式。
     */
    public SafetyMaskPreviewRespVO preview(SafetyMaskPreviewReqVO req) {
        SafetyMaskSceneEnum scene = SafetyMaskSceneEnum.of(req.getSceneCode());
        MetaTableKeyResolver.TableMatch table = tableKeyResolver.getTable(req.getMetaTableId());
        if (table == null) {
            throw exception(SAFETY_MASK_INVALID, "元数据表不存在");
        }
        List<MetaColumnDO> metaColumns = columnMapper.selectListByTableId(table.tableId());
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>(metaColumns.size());
        for (MetaColumnDO metaColumn : metaColumns) {
            SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
            column.setOutputName(metaColumn.getColumnName());
            column.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(table.datasourceId(), table.schemaName(),
                    table.tableName(), metaColumn.getColumnName())));
            columns.add(column);
        }
        SafetyDynamicMaskService.Decision decision = dynamicMaskService.decide(req.getUserId(), scene, columns);
        Map<String, SafetyDynamicMaskRespDTO.MaskedColumn> masked = new LinkedHashMap<>();
        decision.maskedColumns().forEach(column -> masked.put(column.getOutputName(), column));

        Map<String, Object> sampleRow = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(req.getSampleValue())) {
            masked.keySet().forEach(name -> sampleRow.put(name, req.getSampleValue()));
        }
        Map<String, Object> sampled = sampleRow.isEmpty() ? Map.of()
                : decision.policy() == null ? new LinkedHashMap<>(sampleRow)
                : fieldMaskService.apply(decision.policy(), List.of(sampleRow)).getFirst();

        SafetyMaskPreviewRespVO vo = new SafetyMaskPreviewRespVO();
        vo.setSceneEnabled(dynamicMaskService.enabled(scene));
        vo.setGloballyExempt(decision.globallyExempt());
        vo.setPolicyFingerprint(decision.policy() == null ? null : decision.policy().fingerprint());
        for (SafetyDynamicMaskReqDTO.Column column : columns) {
            SafetyMaskPreviewRespVO.Column item = new SafetyMaskPreviewRespVO.Column();
            item.setColumnName(column.getOutputName());
            SafetyDynamicMaskRespDTO.MaskedColumn hit = masked.get(column.getOutputName());
            item.setMasked(hit != null && Boolean.TRUE.equals(vo.getSceneEnabled()));
            if (hit != null) {
                item.setReason(hit.getReason());
                item.setCategoryNames(hit.getCategoryNames());
                if (sampled.containsKey(column.getOutputName())) {
                    Object value = SafetyDynamicMaskRespDTO.REASON_RULE.equals(hit.getReason())
                            ? sampled.get(column.getOutputName())
                            : SafetyMaskEngine.unavailableMask(req.getSampleValue());
                    item.setSampleResult(value == null ? null : String.valueOf(value));
                }
            }
            vo.getColumns().add(item);
        }
        return vo;
    }
}
