package cn.iocoder.yudao.module.governance.api.standard;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardReferenceDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableSnapshotMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.*;

@RestController
@Validated
public class StandardReferenceApiImpl implements StandardReferenceApi {

    @Resource
    private StandardTermMapper terms;

    @Resource
    private StandardElementMapper elements;

    @Resource
    private MetaTableMapper tables;

    @Resource
    private MetaTableSnapshotMapper sourceSnapshots;

    /**
     * 检索平台内可见术语、标准数据元和已发布来源，最多50个对象。
     */
    @Override
    public CommonResult<List<StandardReferenceDTO>> search(String kind, String keyword) {
        var results = new ArrayList<StandardReferenceDTO>();
        if ("TERM".equals(kind)) {
            for (var item : terms.selectList(
                    new LambdaQueryWrapperX<StandardTermDO>().likeIfPresent(StandardTermDO::getName, keyword)
                            .orderByDesc(StandardTermDO::getId).last("LIMIT 50"))) {
                results.add(term(item));
            }
        } else if ("STANDARD".equals(kind)) {
            for (var item : elements.selectList(
                    new LambdaQueryWrapperX<StandardElementDO>().likeIfPresent(StandardElementDO::getName, keyword)
                            .orderByDesc(StandardElementDO::getId).last("LIMIT 50"))) {
                results.add(standard(item));
            }
        } else if ("SOURCE".equals(kind)) {
            for (var item : tables.selectList(
                    new LambdaQueryWrapperX<MetaTableDO>().likeIfPresent(MetaTableDO::getTableName, keyword)
                            .eq(MetaTableDO::getPublishStatus, 1).orderByDesc(MetaTableDO::getId).last("LIMIT 50"))) {
                results.add(source(item));
            }
        }
        return CommonResult.success(results);
    }

    /**
     * 验证指定外部引用及冻结版本。
     */
    @Override
    public CommonResult<StandardReferenceDTO> resolve(String kind, Long id, String version) {
        StandardReferenceDTO result = null;
        if ("TERM".equals(kind)) {
            var item = terms.selectById(id);
            if (item != null) {
                result = term(item);
            }
        } else if ("STANDARD".equals(kind) && id > 0 && id <= Integer.MAX_VALUE) {
            var item = elements.selectById(id.intValue());
            if (item != null) {
                result = standard(item);
            }
        } else if ("SOURCE".equals(kind)) {
            var item = tables.selectById(id);
            if (item != null) {
                result = source(item);
            }
        }
        if (result != null && !Objects.equals(result.getVersion(), version)) {
            result.setActive(false);
            result.setReason("所引用版本已变化，请重新选择");
        }
        return CommonResult.success(result);
    }

    /**
     * 将标准术语组装为跨模块引用响应。
     */
    private StandardReferenceDTO term(StandardTermDO item) {
        var result = base("TERM", item.getId(), item.getName(), item.getDefinition());
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(
                Arrays.asList(item.getId(), item.getName(), item.getEngName(), item.getDefinition(), item.getAlias(),
                        item.getRelatedElementId())));
        result.setVersion(hash);
        result.setEvidenceHash(hash);
        result.setActive(item.getStatus() != null && item.getStatus() == 0);
        result.setReason(result.isActive() ? "术语定义摘要已固定" : "术语已停用");
        return result;
    }

    /**
     * 将标准数据元组装为跨模块引用响应。
     */
    private StandardReferenceDTO standard(StandardElementDO head) {
        var result = base("STANDARD", head.getId().longValue(), head.getName(), head.getDescription());
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(Arrays.asList(head.getId(), head.getCode(),
                head.getName(), head.getEngName(), head.getDescription(), head.getColumnType(), head.getDataLength(),
                head.getDataPrecision(), head.getDataScale(), head.getDataFormat(), head.getMeasureUnit(),
                head.getValueRange(), head.getNullableFlag(), head.getDefaultValue(), head.getCodeSetId(),
                head.getStatus(), head.getLifecycleStatus(), head.getRevision())));
        result.setVersion(hash);
        result.setEvidenceHash(hash);
        result.setActive(head.getStatus() != null && head.getStatus() == 0
                && "PUBLISHED".equals(head.getLifecycleStatus()));
        result.setReason(result.isActive() ? "数据元当前内容摘要" : "标准未发布、已停用或废弃");
        return result;
    }

    /**
     * 读取标准引用来源记录。
     */
    private StandardReferenceDTO source(MetaTableDO item) {
        var result = base("SOURCE", item.getId(), item.getTableName(), item.getBusinessDefinition());
        result.setVersion(String.valueOf(item.getPublishedSnapshotId()));
        var snapshot = item.getPublishedSnapshotId() == null ? null : sourceSnapshots.selectById(
                item.getPublishedSnapshotId());
        boolean validSnapshot = snapshot != null && Objects.equals(snapshot.getTableId(),
                item.getId()) && snapshot.getSnapshot() != null && !snapshot.getSnapshot().isBlank();
        result.setEvidenceHash(DigestUtil.sha256Hex(
                JsonUtils.toJsonString(Arrays.asList(item.getId(), item.getPublishedSnapshotId(), item.getResourceUrn(),
                        item.getTableName(), item.getBusinessDefinition(),
                        validSnapshot ? snapshot.getSnapshot() : null))));
        result.setActive(Objects.equals(1, item.getPublishStatus()) && Objects.equals(1,
                item.getObjectStatus()) && validSnapshot);
        result.setReason(result.isActive() ? "已核实归属的来源发布快照" : "来源未发布、快照不存在 / 归属不符或已失效");
        return result;
    }

    /**
     * 设置标准引用响应的基础标识和展示属性。
     */
    private StandardReferenceDTO base(String kind, Long id, String name, String definition) {
        var result = new StandardReferenceDTO();
        result.setKind(kind);
        result.setId(id);
        result.setName(name);
        result.setDefinition(definition);
        return result;
    }
}
