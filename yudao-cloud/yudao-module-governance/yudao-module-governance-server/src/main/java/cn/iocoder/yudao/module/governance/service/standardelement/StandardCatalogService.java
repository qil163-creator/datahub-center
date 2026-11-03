package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 平台共享标准目录的引用验证与展示富化，不改变已发布快照。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardCatalogService {

    private final CategoryDatametaMapper categories;

    private final StandardDocMapper documents;

    private final AdminUserApi users;

    /**
     * 读取标准目录分类并组装展示信息。
     */
    public List<CategoryDatametaRespVO> categoryList() {
        return BeanUtils.toBean(categories.selectList(), CategoryDatametaRespVO.class);
    }

    /**
     * 补充标准目录的文档。
     */
    public void fillDocuments(List<StandardDocRespVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var people = documentCreators(rows);
        var categoryIds = rows.stream().map(StandardDocRespVO::getCategoryId).filter(Objects::nonNull).distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            categories.selectByIds(categoryIds)
                    .forEach(category -> names.put(category.getId(), category.getCategoryName()));
        }
        rows.forEach(row -> {
            row.setCreatorName(name(people, row.getCreator()));
            row.setCategoryName(names.get(row.getCategoryId()));
        });
    }

    /**
     * 批量解析标准文档创建人显示信息。
     */
    private Map<Long, AdminUserRespDTO> documentCreators(List<StandardDocRespVO> rows) {
        try {
            return userMap(rows.stream().map(StandardDocRespVO::getCreator).toList(), List.of());
        } catch (RuntimeException exception) {
            // 创建人名称仅用于展示，用户服务异常时仍允许查询标准文件及其类目。
            log.warn("[fillDocuments][创建人名称查询失败，保留编号展示，异常类型：{}]",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 查询指定标准目录及其全部后代编号。
     */
    public List<Long> descendants(Long id) {
        if (id == null) {
            return null;
        }
        List<CategoryDatametaDO> all = categories.selectList();
        if (all.stream().noneMatch(row -> id.equals(row.getId()))) {
            return List.of(-1L);
        }
        Set<Long> selected = new LinkedHashSet<>(List.of(id));
        boolean changed;
        do {
            changed = false;
            for (var row : all) {
                if (selected.contains(row.getParentId())) {
                    changed |= selected.add(row.getId());
                }
            }
        } while (changed);
        return List.copyOf(selected);
    }

    /** 用户 RPC 必须在取得本地行锁之前调用。联系电话不信任客户端。 */
    public String ownerContact(Long owner) {
        if (owner == null) {
            return null;
        }
        var user = users.getUser(owner).getCheckedData();
        if (user == null || !Objects.equals(user.getStatus(), 0)) {
            throw exception(STANDARD_CATALOG_INVALID, "负责人不存在或已停用");
        }
        return user.getMobile();
    }

    /**
     * 校验标准目录配置满足当前业务约束。
     */
    public void validate(Long categoryId, Long documentId, String standardType, Integer length, Integer scale) {
        if (categoryId != null && categories.selectById(categoryId) == null) {
            throw exception(STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
        if (length != null && (length < 1 || length > 4000) || scale != null && (scale < 0 || scale > 18)) {
            throw exception(STANDARD_CATALOG_INVALID, "长度须为 1 至 4000，小数位数须为 0 至 18");
        }
        if (documentId != null) {
            var document = documents.selectById(documentId);
            if (document == null || standardType != null && !standardType.equals(document.getStdType())) {
                throw exception(STANDARD_CATALOG_INVALID, "标准文件不存在或不属于所选标准类型");
            }
        }
    }

    /**
     * 检查标准目录当前修订号与请求预期一致。
     */
    public static void checkRevision(Long requested, Long current) {
        // 写入必须携带读取版本，禁止遗漏版本绕过并发冲突检测。
        if (requested == null || !Objects.equals(requested, current == null ? 0L : current)) {
            throw exception(STANDARD_CATALOG_CONFLICT);
        }
    }

    /**
     * 补充标准目录的数据元。
     */
    public void fillElements(List<StandardElementRespVO> rows) {
        var people = userMap(rows.stream().map(StandardElementRespVO::getCreator).toList(),
                java.util.stream.Stream.concat(rows.stream().map(StandardElementRespVO::getPersonCharge),
                        rows.stream().map(row -> parseId(row.getUpdater()))).toList());
        for (var row : rows) {
            row.setCreatorName(name(people, row.getCreator()));
            row.setUpdaterName(name(people, row.getUpdater()));
            row.setPersonChargeName(
                    name(people, row.getPersonCharge() == null ? null : row.getPersonCharge().toString()));
            var category = row.getCategoryId() == null ? null : categories.selectById(row.getCategoryId());
            row.setCategoryName(category == null ? null : category.getCategoryName());
            var doc = row.getDocumentId() == null ? null : documents.selectById(row.getDocumentId());
            row.setDocumentName(doc == null ? null : doc.getNameCn());
        }
    }

    /**
     * 为标准代码集补充创建人、更新人、负责人、分类和关联文档名称。
     */
    public void fillCodeSets(List<StandardCodeSetRespVO> rows) {
        var people = userMap(rows.stream().map(StandardCodeSetRespVO::getCreator).toList(),
                java.util.stream.Stream.concat(rows.stream().map(StandardCodeSetRespVO::getPersonCharge),
                        rows.stream().map(row -> parseId(row.getUpdater()))).toList());
        for (var row : rows) {
            row.setCreatorName(name(people, row.getCreator()));
            row.setUpdaterName(name(people, row.getUpdater()));
            row.setPersonChargeName(
                    name(people, row.getPersonCharge() == null ? null : row.getPersonCharge().toString()));
            var category = row.getCategoryId() == null ? null : categories.selectById(row.getCategoryId());
            row.setCategoryName(category == null ? null : category.getCategoryName());
            var doc = row.getDocumentId() == null ? null : documents.selectById(row.getDocumentId());
            row.setDocumentName(doc == null ? null : doc.getNameCn());
        }
    }

    /**
     * 按编号建立标准目录相关用户的显示名称映射。
     */
    public Map<Long, AdminUserRespDTO> userMap(List<String> creators, List<Long> owners) {
        Set<Long> ids = new HashSet<>();
        owners.stream().filter(Objects::nonNull).forEach(ids::add);
        for (String value : creators) {
            try {
                if (value != null) {
                    ids.add(Long.valueOf(value));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ids.isEmpty() ? Map.of() : users.getUserMap(ids);
    }

    /**
     * 解析标准目录的显示名称。
     */
    public static String name(Map<Long, AdminUserRespDTO> users, String id) {
        if (id == null) {
            return null;
        }
        try {
            var user = users.get(Long.valueOf(id));
            return user == null ? id : user.getNickname();
        } catch (NumberFormatException ignored) {
            return id;
        }
    }

    /**
     * 解析标准目录的编号。
     */
    private static Long parseId(String id) {
        try {
            return id == null ? null : Long.valueOf(id);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 补充标准目录的明细。
     */
    public void fillItems(
            List<cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemRespVO> rows) {
        var people = userMap(rows.stream()
                .map(cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemRespVO::getCreator)
                .toList(), rows.stream().map(row -> parseId(row.getUpdater())).toList());
        rows.forEach(row -> {
            row.setCreatorName(name(people, row.getCreator()));
            row.setUpdaterName(name(people, row.getUpdater()));
        });
    }
}
