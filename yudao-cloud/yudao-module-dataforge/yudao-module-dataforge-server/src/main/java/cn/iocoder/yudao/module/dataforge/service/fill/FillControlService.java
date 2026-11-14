package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAttachmentDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAttachmentMapper;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在线填报-控件运行期能力：人员/部门可选项、附件名称、级联与编号类字段的提交校验、附件绑定提交。
 *
 * <p>人员、部门、附件、图片字段的单元格存编号（附件可多个，英文逗号分隔）；
 * 人员、部门的可选范围按字段配置取报送组织及下级，或全部启用的人员/部门。</p>
 *
 * @author Li
 */
@Component
public class FillControlService {

    /** 根部门编号（全部部门） */
    private static final Long ROOT_DEPT_ID = 0L;

    private static final int STATUS_ENABLED = 0;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private FillAttachmentMapper attachmentMapper;

    @Resource
    private FillHierarchyService hierarchyService;

    /** 可选项：value 为编号，label 为显示名称 */
    public record Option(String value, String label) {
    }

    /** 控件校验问题；rowIndex 从 0 开始 */
    public record Issue(String ruleCode, int rowIndex, String fieldCode, String message) {
    }

    // ================================================================ 工作台显示

    /**
     * 工作台所需的编号类字段选项：人员/部门为可选范围，附件/图片为草稿中已引用附件的名称。
     *
     * @param item 工作项
     * @param fields 模板 fieldSchema
     * @param rows 草稿行数据
     * @return fieldCode → 选项
     */
    public Map<String, List<Option>> resolveOptions(FillWorkItemDO item, List<Map<String, Object>> fields,
                                                    List<Map<String, Object>> rows) {
        Map<String, List<Option>> result = new LinkedHashMap<>();
        Map<String, List<Option>> personCache = new HashMap<>();
        for (Map<String, Object> field : fields) {
            String kind = stringOf(field.get("refKind"));
            String code = stringOf(field.get("fieldCode"));
            if ("USER".equals(kind) || "DEPT".equals(kind)) {
                String scope = "ALL".equals(field.get("refScope")) ? "ALL" : "ORG";
                result.put(code, personCache.computeIfAbsent(kind + ":" + scope,
                        key -> personOptions(kind, scope, item.getReportingOrgId())));
            } else if ("ATTACHMENT".equals(kind) || "IMAGE".equals(kind)) {
                Set<Long> ids = new LinkedHashSet<>();
                rows.forEach(row -> ids.addAll(parseIds(row.get(code))));
                List<Option> options = new ArrayList<>();
                if (!ids.isEmpty()) {
                    Set<Long> owners = attachmentOwners(item);
                    for (FillAttachmentDO attachment : attachmentMapper.selectByIds(ids)) {
                        if (owners.contains(attachment.getWorkItemId())
                                && FillAttachmentDO.STATE_READY.equals(attachment.getState())) {
                            options.add(new Option(String.valueOf(attachment.getId()), attachment.getName()));
                        }
                    }
                }
                result.put(code, options);
            }
        }
        return result;
    }

    /**
     * 只读查看（审批、提交历史）用的显示名称：只解析快照中实际出现的编号，不返回可选范围。
     *
     * <p>人员、部门按编号查询当前名称（已停用的人员、部门也显示，历史记录不因人员变动而无法辨认）；
     * 附件、图片只认属于该工作项且未删除的登记。查不到的编号不返回，前端显示为“未知（编号）”。</p>
     *
     * @param item 工作项
     * @param fields 模板 fieldSchema
     * @param rows 快照行
     * @return fieldCode → 编号与名称
     */
    public Map<String, List<Option>> resolveLabels(FillWorkItemDO item, List<Map<String, Object>> fields,
                                                   List<Map<String, Object>> rows) {
        Map<String, Set<Long>> idsByField = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String kind = stringOf(field.get("refKind"));
            if (!"USER".equals(kind) && !"DEPT".equals(kind) && !"ATTACHMENT".equals(kind) && !"IMAGE".equals(kind)) {
                continue;
            }
            String code = stringOf(field.get("fieldCode"));
            Set<Long> ids = new LinkedHashSet<>();
            rows.forEach(row -> ids.addAll(parseIds(row.get(code))));
            if (!ids.isEmpty()) {
                idsByField.put(code, ids);
                kinds.put(code, kind);
            }
        }
        if (idsByField.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> deptIds = new LinkedHashSet<>();
        Set<Long> attachmentIds = new LinkedHashSet<>();
        idsByField.forEach((code, ids) -> {
            switch (kinds.get(code)) {
                case "USER" -> userIds.addAll(ids);
                case "DEPT" -> deptIds.addAll(ids);
                default -> attachmentIds.addAll(ids);
            }
        });
        Map<Long, String> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            nullToEmpty(adminUserApi.getUserList(userIds).getCheckedData())
                    .forEach(user -> users.put(user.getId(), StrUtil.blankToDefault(user.getNickname(),
                            String.valueOf(user.getId()))));
        }
        Map<Long, String> depts = new HashMap<>();
        if (!deptIds.isEmpty()) {
            nullToEmpty(deptApi.getDeptList(deptIds).getCheckedData())
                    .forEach(dept -> depts.put(dept.getId(), dept.getName()));
        }
        Map<Long, String> attachments = new HashMap<>();
        if (!attachmentIds.isEmpty()) {
            Set<Long> owners = item == null ? Set.of() : attachmentOwners(item);
            for (FillAttachmentDO attachment : nullToEmpty(attachmentMapper.selectByIds(attachmentIds))) {
                if (item != null && owners.contains(attachment.getWorkItemId())
                        && !FillAttachmentDO.STATE_DELETED.equals(attachment.getState())) {
                    attachments.put(attachment.getId(), attachment.getName());
                }
            }
        }
        Map<String, List<Option>> result = new LinkedHashMap<>();
        idsByField.forEach((code, ids) -> {
            Map<Long, String> names = switch (kinds.get(code)) {
                case "USER" -> users;
                case "DEPT" -> depts;
                default -> attachments;
            };
            List<Option> options = new ArrayList<>();
            ids.forEach(id -> {
                String name = names.get(id);
                if (name != null) {
                    options.add(new Option(String.valueOf(id), name));
                }
            });
            result.put(code, options);
        });
        return result;
    }

    /** 附件归属：本实例及其各级下级（汇总行引用的是下级上传的附件） */
    private Set<Long> attachmentOwners(FillWorkItemDO item) {
        if (hierarchyService == null || item.getTaskId() == null) {
            return Set.of(item.getId());
        }
        return hierarchyService.subtreeItemIds(item);
    }

    // ================================================================ 提交校验

    /**
     * 预取人员、部门字段的可选范围（远程调用）；提交时在获取行锁之前调用，避免持锁期间等待远程服务。
     *
     * @param item 工作项
     * @param fields 模板 fieldSchema
     * @return kind:scope → 可选编号
     */
    public Map<String, Set<String>> preloadPersonScopes(FillWorkItemDO item, List<Map<String, Object>> fields) {
        Map<String, Set<String>> scopes = new HashMap<>();
        for (Map<String, Object> field : fields) {
            String kind = stringOf(field.get("refKind"));
            if ("USER".equals(kind) || "DEPT".equals(kind)) {
                String scope = "ALL".equals(field.get("refScope")) ? "ALL" : "ORG";
                scopes.computeIfAbsent(kind + ":" + scope, key -> {
                    Set<String> values = new LinkedHashSet<>();
                    personOptions(kind, scope, item.getReportingOrgId()).forEach(option -> values.add(option.value()));
                    return values;
                });
            }
        }
        return scopes;
    }

    /**
     * 级联、人员、部门、附件、图片字段的提交校验。
     *
     * @param item 工作项
     * @param fields 模板 fieldSchema
     * @param rows 行数据（已完成公式重算）
     * @return 问题列表
     */
    public List<Issue> validate(FillWorkItemDO item, List<Map<String, Object>> fields, List<Map<String, Object>> rows,
                                Map<String, Set<String>> personScopes) {
        List<Issue> issues = new ArrayList<>();
        Map<Long, FillAttachmentDO> attachments = loadReferencedAttachments(fields, rows);
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            for (Map<String, Object> field : fields) {
                String code = stringOf(field.get("fieldCode"));
                String name = StrUtil.blankToDefault(stringOf(field.get("name")), code);
                String text = StrUtil.trimToEmpty(stringOf(row.get(code)));
                if (text.isEmpty()) {
                    continue;
                }
                String kind = stringOf(field.get("refKind"));
                if (field.get("cascadeParent") != null && !cascadeAllowed(field, row, text)) {
                    issues.add(new Issue("CASCADE", index, code, String.format("第 %s 行【%s】与上级字段的取值不匹配", index + 1, name)));
                } else if ("USER".equals(kind) || "DEPT".equals(kind)) {
                    String scope = "ALL".equals(field.get("refScope")) ? "ALL" : "ORG";
                    Set<String> allowed = personScopes.getOrDefault(kind + ":" + scope, Set.of());
                    if (!allowed.contains(text)) {
                        issues.add(new Issue("USER".equals(kind) ? "USER_SCOPE" : "DEPT_SCOPE", index, code,
                                String.format("第 %s 行【%s】不在可选的%s范围内", index + 1, name,
                                        "USER".equals(kind) ? "人员" : "部门")));
                    }
                } else if ("ATTACHMENT".equals(kind) || "IMAGE".equals(kind)) {
                    if (!attachmentsValid(item, code, kind, text, attachments)) {
                        issues.add(new Issue("ATTACHMENT", index, code,
                                String.format("第 %s 行【%s】的附件无效，请重新上传", index + 1, name)));
                    }
                }
            }
        }
        return issues;
    }

    /**
     * 提交成功后绑定快照引用的附件：绑定后不再过期。
     *
     * @param fields 模板 fieldSchema
     * @param rows 快照行数据
     * @param submissionId 提交编号
     */
    public void bindAttachments(List<Map<String, Object>> fields, List<Map<String, Object>> rows, Long submissionId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> field : fields) {
            String kind = stringOf(field.get("refKind"));
            if ("ATTACHMENT".equals(kind) || "IMAGE".equals(kind)) {
                String code = stringOf(field.get("fieldCode"));
                rows.forEach(row -> ids.addAll(parseIds(row.get(code))));
            }
        }
        attachmentMapper.bindSubmission(ids, submissionId);
    }

    // ================================================================ 内部

    private boolean cascadeAllowed(Map<String, Object> field, Map<String, Object> row, String value) {
        String parent = StrUtil.trimToEmpty(stringOf(row.get(stringOf(field.get("cascadeParent")))));
        if (parent.isEmpty()) {
            // 上级未填时只校验取值在全部可选项内（由字典规则负责）
            return true;
        }
        if (!(field.get("cascadeOptions") instanceof Map<?, ?> options)
                || !(options.get(parent) instanceof List<?> children)) {
            return false;
        }
        return children.stream().anyMatch(child -> value.equals(String.valueOf(child)));
    }

    private boolean attachmentsValid(FillWorkItemDO item, String fieldCode, String kind, String text,
                                     Map<Long, FillAttachmentDO> attachments) {
        List<Long> ids = parseIds(text);
        if (ids.isEmpty() || ids.size() != StrUtil.split(text, ',', true, true).size()) {
            return false;
        }
        for (Long id : ids) {
            FillAttachmentDO attachment = attachments.get(id);
            if (attachment == null || !FillAttachmentDO.STATE_READY.equals(attachment.getState())
                    || !Objects.equals(attachment.getWorkItemId(), item.getId())
                    || !Objects.equals(attachment.getFieldCode(), fieldCode)
                    || ("IMAGE".equals(kind) && !StrUtil.startWith(attachment.getContentType(), "image/"))) {
                return false;
            }
        }
        return true;
    }

    private Map<Long, FillAttachmentDO> loadReferencedAttachments(List<Map<String, Object>> fields,
                                                                  List<Map<String, Object>> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> field : fields) {
            String kind = stringOf(field.get("refKind"));
            if ("ATTACHMENT".equals(kind) || "IMAGE".equals(kind)) {
                String code = stringOf(field.get("fieldCode"));
                rows.forEach(row -> ids.addAll(parseIds(row.get(code))));
            }
        }
        Map<Long, FillAttachmentDO> result = new HashMap<>();
        if (!ids.isEmpty()) {
            attachmentMapper.selectByIds(ids).forEach(attachment -> result.put(attachment.getId(), attachment));
        }
        return result;
    }

    /** 人员或部门可选项：ORG = 报送组织及其下级；ALL = 全部启用 */
    private List<Option> personOptions(String kind, String scope, Long orgId) {
        List<DeptRespDTO> depts = new ArrayList<>();
        if ("ALL".equals(scope) || orgId == null) {
            depts.addAll(nullToEmpty(deptApi.getChildDeptList(ROOT_DEPT_ID).getCheckedData()));
        } else {
            DeptRespDTO self = deptApi.getDept(orgId).getCheckedData();
            if (self != null) {
                depts.add(self);
            }
            depts.addAll(nullToEmpty(deptApi.getChildDeptList(orgId).getCheckedData()));
        }
        List<DeptRespDTO> enabledDepts = depts.stream()
                .filter(dept -> dept.getStatus() == null || dept.getStatus() == STATUS_ENABLED).toList();
        if ("DEPT".equals(kind)) {
            return enabledDepts.stream().map(dept -> new Option(String.valueOf(dept.getId()), dept.getName())).toList();
        }
        List<AdminUserRespDTO> users = "ALL".equals(scope) || orgId == null
                ? nullToEmpty(adminUserApi.getUserListByStatus(STATUS_ENABLED).getCheckedData())
                : nullToEmpty(adminUserApi.getUserListByDeptIds(
                        enabledDepts.stream().map(DeptRespDTO::getId).toList()).getCheckedData());
        return users.stream()
                .filter(user -> user.getStatus() == null || user.getStatus() == STATUS_ENABLED)
                .map(user -> new Option(String.valueOf(user.getId()), user.getNickname()))
                .toList();
    }

    /** 英文逗号分隔的编号；非数字片段忽略（由调用方按数量比对判定为非法） */
    static List<Long> parseIds(Object value) {
        List<Long> ids = new ArrayList<>();
        for (String part : StrUtil.split(StrUtil.trimToEmpty(stringOf(value)), ',', true, true)) {
            if (part.matches("\\d{1,19}")) {
                try {
                    ids.add(Long.valueOf(part));
                } catch (NumberFormatException ignored) {
                    // 超出范围按非法处理
                }
            }
        }
        return ids;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
