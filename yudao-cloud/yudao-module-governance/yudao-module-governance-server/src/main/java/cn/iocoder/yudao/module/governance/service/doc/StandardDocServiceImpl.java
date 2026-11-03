package cn.iocoder.yudao.module.governance.service.doc;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardversion.StandardObjectVersionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardversion.StandardObjectVersionMapper;
import cn.iocoder.yudao.module.infra.api.file.FileApi;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据标准--标准文件登记 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class StandardDocServiceImpl implements StandardDocService {

    private static final String REGISTRY_NO_PREFIX = "STD";

    private static final DateTimeFormatter REGISTRY_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private static final int REGISTRY_SEQ_WIDTH = 4;

    private static final int REGISTRY_NO_MAX_RETRY = 20;

    @Resource
    private StandardDocMapper docMapper;

    @Resource
    private StandardObjectVersionMapper objectVersionMapper;

    @Resource
    private FileApi fileApi;

    @Resource
    private StandardCatalogService catalogService;

    @Resource
    private CategoryDatametaMapper categoryMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建数据标准--标准文件登记
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    public Long createDoc(StandardDocSaveReqVO createReqVO) {
        // 先确认文件已经由 infra 登记；文件 RPC 不进入本地持锁事务。
        String fileUrl = createReqVO.getFileId() == null ? createReqVO.getFileUrl() : resolveRegisteredFile(
                createReqVO.getFileId());
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            validateAndLockCategory(createReqVO.getCategoryId());
            validateAndLockReplaceDoc(null, createReqVO.getReplaceDocId());
            LocalDateTime registryTime = LocalDateTime.now();
            StandardDocDO doc = BeanUtils.toBean(createReqVO, StandardDocDO.class);
            doc.setId(null);
            doc.setFileUrl(fileUrl);
            // 入库编号、入库时间由后端按规则生成，忽略前端传入值。
            doc.setRegistryNo(generateRegistryNo(registryTime.toLocalDate()));
            doc.setRegistryTime(registryTime);
            docMapper.insert(doc);
            return doc.getId();
        });
    }

    /**
     * 更新数据标准--标准文件登记
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    public void updateDoc(StandardDocSaveReqVO updateReqVO) {
        updateDoc(updateReqVO, true);
    }

    /**
     * 按登记工作台要求校验类目、原文和标准编号后创建。
     */
    @Override
    public Long registerDoc(StandardDocSaveReqVO request) {
        validateRegistration(request);
        validateDates(request);
        return createDoc(request);
    }

    /**
     * 登记工作台提交完整元信息，允许清空版本号、废止日期。
     */
    @Override
    public void updateRegisteredDoc(StandardDocSaveReqVO request) {
        validateRegistration(request);
        validateDates(request);
        updateDoc(request, false);
    }

    /**
     * 校验标准文档的登记，不满足约束时拒绝当前操作。
     */
    private static void validateRegistration(StandardDocSaveReqVO request) {
        if (request.getCategoryId() == null) {
            throw exception(STANDARD_CATALOG_INVALID, "标准类目不能为空");
        }
        if (request.getFileId() == null) {
            throw exception(DOC_FILE_NOT_EXISTS);
        }
        if (StrUtil.isBlank(request.getStdCode())) {
            throw exception(STANDARD_CATALOG_INVALID, "标准编号不能为空");
        }
        if (StrUtil.isBlank(request.getNameCn())) {
            throw exception(STANDARD_CATALOG_INVALID, "标准名称不能为空");
        }
    }

    /**
     * 更新文档。
     */
    private void updateDoc(StandardDocSaveReqVO updateReqVO, boolean preserveMissingRegistrationFields) {
        StandardDocDO before = validateDocExists(updateReqVO.getId());
        Long categoryId = updateReqVO.getCategoryId() == null ? before.getCategoryId() : updateReqVO.getCategoryId();
        String fileUrl = updateReqVO.getFileId() == null ? null : resolveRegisteredFile(updateReqVO.getFileId());
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            validateAndLockCategory(categoryId);
            StandardDocDO existingDoc = validateAndLockUpdateDocs(
                    updateReqVO.getId(), updateReqVO.getReplaceDocId());
            if (updateReqVO.getCategoryId() == null && !Objects.equals(before.getCategoryId(),
                    existingDoc.getCategoryId())) {
                throw exception(STANDARD_CATALOG_CONFLICT);
            }
            if (!Objects.equals(existingDoc.getStdType(), updateReqVO.getStdType())
                    && (docMapper.selectElementReferenceCount(existingDoc.getId()) > 0
                    || docMapper.selectCodeSetReferenceCount(existingDoc.getId()) > 0)) {
                throw exception(STANDARD_CATALOG_INVALID, "已有数据元或代码表引用的标准文件不能变更标准类型");
            }
            StandardDocDO updateObj = BeanUtils.toBean(updateReqVO, StandardDocDO.class);
            // 原检索页不采集新增字段；专用登记接口才以完整表单语义清空可选字段。
            if (preserveMissingRegistrationFields) {
                if (updateReqVO.getVersionNo() == null) {
                    updateObj.setVersionNo(existingDoc.getVersionNo());
                }
                if (updateReqVO.getAbolishDate() == null) {
                    updateObj.setAbolishDate(existingDoc.getAbolishDate());
                }
            }
            // 原检索页保留未分类记录和移除附件的原有语义；已登记文件的 URL 由 infra 决定。
            if (updateReqVO.getCategoryId() == null) {
                updateObj.setCategoryId(existingDoc.getCategoryId());
            }
            if (updateReqVO.getFileId() != null) {
                updateObj.setFileUrl(fileUrl);
                if (Objects.equals(updateReqVO.getFileId(), existingDoc.getFileId())
                        && StrUtil.isBlank(updateReqVO.getFileName())) {
                    updateObj.setFileName(existingDoc.getFileName());
                }
            }
            // 入库编号、入库时间创建后不可变更。
            updateObj.setRegistryNo(existingDoc.getRegistryNo());
            updateObj.setRegistryTime(existingDoc.getRegistryTime());
            docMapper.updateById(updateObj);
        });
    }

    /**
     * 解析标准文档的已登记文件。
     */
    private String resolveRegisteredFile(Long fileId) {
        if (fileId <= 0) {
            throw exception(DOC_FILE_NOT_EXISTS);
        }
        return validateFileUrl(fileApi.getFileAccessUrl(fileId, null).getCheckedData());
    }

    /**
     * 校验标准文档的与锁定分类，不满足约束时拒绝当前操作。
     */
    private void validateAndLockCategory(Long categoryId) {
        if (categoryId != null && categoryMapper.selectByIdForUpdate(categoryId) == null) {
            throw exception(STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
    }

    /**
     * 校验标准文档的日期，不满足约束时拒绝当前操作。
     */
    private static void validateDates(StandardDocSaveReqVO request) {
        if (request.getPublishDate() != null && request.getImplementDate() != null
                && request.getImplementDate().isBefore(request.getPublishDate())) {
            throw exception(STANDARD_CATALOG_INVALID, "实施日期不能早于发布日期");
        }
        LocalDate startDate = request.getImplementDate() == null ? request.getPublishDate() : request.getImplementDate();
        if (startDate != null && request.getAbolishDate() != null && request.getAbolishDate().isBefore(startDate)) {
            throw exception(STANDARD_CATALOG_INVALID, "废止日期不能早于发布或实施日期");
        }
    }

    /**
     * 删除数据标准--标准文件登记
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDoc(Long id) {
        validateAndLockDocExists(id);
        validateDocNotReferenced(id);
        docMapper.deleteById(id);
    }

    /**
     * 批量删除数据标准--标准文件登记
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocListByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.stream().anyMatch(id -> id == null)) {
            throw exception(DOC_NOT_EXISTS);
        }
        List<Long> sortedIds = ids.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        sortedIds.forEach(this::validateAndLockDocExists);
        sortedIds.forEach(this::validateDocNotReferenced);
        docMapper.deleteByIds(sortedIds);
    }

    /**
     * 校验文档记录存在，不存在时抛出业务异常。
     */
    private StandardDocDO validateDocExists(Long id) {
        StandardDocDO doc = docMapper.selectById(id);
        if (doc == null) {
            throw exception(DOC_NOT_EXISTS);
        }
        return doc;
    }

    /**
     * 校验标准文档的与锁定更新文档，不满足约束时拒绝当前操作。
     */
    private StandardDocDO validateAndLockUpdateDocs(Long id, Long replaceDocId) {
        if (id == null) {
            throw exception(DOC_NOT_EXISTS);
        }
        if (id.equals(replaceDocId)) {
            throw exception(DOC_REPLACE_INVALID);
        }
        List<Long> lockIds = new ArrayList<>();
        lockIds.add(id);
        if (replaceDocId != null) {
            lockIds.add(replaceDocId);
        }
        lockIds.sort(Comparator.naturalOrder());

        StandardDocDO existingDoc = null;
        for (Long lockId : lockIds) {
            StandardDocDO lockedDoc = docMapper.selectByIdForUpdate(lockId);
            if (lockedDoc == null) {
                throw exception(lockId.equals(id) ? DOC_NOT_EXISTS : DOC_REPLACE_INVALID);
            }
            if (lockId.equals(id)) {
                existingDoc = lockedDoc;
            }
        }
        return existingDoc;
    }

    /**
     * 校验标准文档的与锁定文档记录存在，不存在时抛出业务异常。
     */
    private StandardDocDO validateAndLockDocExists(Long id) {
        StandardDocDO doc = docMapper.selectByIdForUpdate(id);
        if (doc == null) {
            throw exception(DOC_NOT_EXISTS);
        }
        return doc;
    }

    /**
     * 锁定待替换标准文档并校验替换关系，保护文档版本切换的一致性。
     */
    private void validateAndLockReplaceDoc(Long id, Long replaceDocId) {
        if (replaceDocId == null) {
            return;
        }
        if (replaceDocId.equals(id)
                || docMapper.selectByIdForUpdate(replaceDocId) == null) {
            throw exception(DOC_REPLACE_INVALID);
        }
    }

    /**
     * 校验标准文档的文档非被引用，不满足约束时拒绝当前操作。
     */
    private void validateDocNotReferenced(Long id) {
        // 写入替代关系、数据元或代码表的 document_id 时，必须在同一事务内
        // 先调用 selectByIdForUpdate 锁定被引用标准。
        if (docMapper.selectCountByReplaceDocId(id) > 0
                || docMapper.selectElementReferenceCount(id) > 0
                || docMapper.selectCodeSetReferenceCount(id) > 0) {
            throw exception(DOC_REFERENCED);
        }
    }

    /**
     * 生成入库编号：STD + yyyyMMdd + 4 位流水号，例如 STD202607230001。
     */
    private String generateRegistryNo(LocalDate date) {
        String prefix = REGISTRY_NO_PREFIX + date.format(REGISTRY_DATE_FORMAT);
        for (int attempt = 0; attempt < REGISTRY_NO_MAX_RETRY; attempt++) {
            String maxRegistryNo = docMapper.selectMaxRegistryNoByPrefix(prefix);
            int nextSeq = parseRegistrySeq(maxRegistryNo, prefix) + 1;
            if (nextSeq > 9999) {
                throw exception(DOC_REGISTRY_NO_DUPLICATE);
            }
            String candidate = prefix + String.format("%0" + REGISTRY_SEQ_WIDTH + "d", nextSeq);
            if (docMapper.selectCountByRegistryNo(candidate, null) == 0) {
                return candidate;
            }
        }
        throw exception(DOC_REGISTRY_NO_DUPLICATE);
    }

    /**
     * 解析标准文档的登记表序列。
     */
    private static int parseRegistrySeq(String registryNo, String prefix) {
        if (registryNo == null || registryNo.length() != prefix.length() + REGISTRY_SEQ_WIDTH
                || !registryNo.startsWith(prefix)) {
            return 0;
        }
        try {
            return Integer.parseInt(registryNo.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 获得数据标准--标准文件登记
     *
     *  @param id 编号
     *  @return 数据标准--标准文件登记
     */
    @Override
    public StandardDocDO getDoc(Long id) {
        return validateDocExists(id);
    }

    /**
     * 获得标准原文的最新可访问 URL
     *
     *  @param id 标准文件编号
     *  @return 最新可访问 URL
     */
    @Override
    public String getDocPreviewUrl(Long id) {
        StandardDocDO doc = validateDocExists(id);
        if (doc.getFileId() != null) {
            return validateFileUrl(fileApi.getFileAccessUrl(doc.getFileId(), null).getCheckedData());
        }
        if (StrUtil.isNotEmpty(doc.getFileUrl())) {
            return validateFileUrl(doc.getFileUrl());
        }
        throw exception(DOC_FILE_NOT_EXISTS);
    }

    /**
     * 校验标准文档的文件URL，不满足约束时拒绝当前操作。
     */
    private static String validateFileUrl(String fileUrl) {
        if (StrUtil.isNotEmpty(fileUrl)) {
            try {
                String scheme = new URI(fileUrl).getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return fileUrl;
                }
            } catch (URISyntaxException ignored) {
                // 统一转换为业务异常
            }
        }
        throw exception(DOC_FILE_URL_INVALID);
    }

    /**
     * 获得数据标准--标准文件登记分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准文件登记分页
     */
    @Override
    public PageResult<StandardDocDO> getDocPage(StandardDocPageReqVO pageReqVO) {
        pageReqVO.setCategoryIds(catalogService.descendants(pageReqVO.getCategoryId()));
        return docMapper.selectPage(pageReqVO);
    }

    /**
     * 填充平台共享标准目录的类目和创建人名称。
     */
    @Override
    public void fillDisplayNames(List<StandardDocRespVO> rows) {
        catalogService.fillDocuments(rows);
    }

    /**
     * 获得替代标准候选精简列表
     *
     *  @param excludeId 排除的标准文件编号
     *  @return 标准文件候选列表
     */
    @Override
    public List<StandardDocDO> getDocSimpleList(Long excludeId) {
        return docMapper.selectSimpleList(excludeId);
    }

    /**
     * 生成标准文件版本快照（版本号按文件递增）
     *
     *  @param id            标准文件编号
     *  @param changeSummary 变更说明
     *  @return 新版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer publishDocVersion(Long id, String changeSummary) {
        StandardDocDO doc = validateAndLockDocExists(id);
        int nextVersion = objectVersionMapper.selectMaxVersionByObject(
                StandardObjectVersionDO.TYPE_DOC, id) + 1;
        objectVersionMapper.insert(StandardObjectVersionDO.builder()
                .objectType(StandardObjectVersionDO.TYPE_DOC)
                .objectId(id)
                .version(nextVersion)
                .snapshot(JsonUtils.toJsonString(doc))
                .changeSummary(StrUtil.blankToDefault(changeSummary, "生成版本快照"))
                .build());
        return nextVersion;
    }

    /**
     * 获得标准文件版本快照列表（按版本号降序）
     *
     *  @param id 标准文件编号
     *  @return 版本快照列表
     */
    @Override
    public List<StandardObjectVersionDO> getDocVersionList(Long id) {
        validateDocExists(id);
        return objectVersionMapper.selectListByObject(StandardObjectVersionDO.TYPE_DOC, id);
    }

}
