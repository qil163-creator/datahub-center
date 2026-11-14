package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillAttachmentRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAttachmentDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAttachmentMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDraftRowMapper;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-附件：上传（两阶段登记）、下载与过期清理。
 *
 * <p>上传：先落 PENDING 意图，再调用 infra 受管文件上传并核对回执（大小、SHA-256），通过后置 READY；
 * 上传失败或超时留下的意图由清理任务按操作键对账。下载：工作项查看权限，或该附件所属提交快照的查看权限
 * （含审批流程参与者）。数据范围：附件只属于一个组织实例，跨实例引用在提交时被拒绝。</p>
 *
 * @author Li
 */
@Slf4j
@Service
public class FillAttachmentService {

    /** 图片上限 5MB，附件上限 20MB（受管文件单文件上限） */
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    /** 单个组织实例的附件数量上限（含未清理的过期附件） */
    private static final long MAX_PER_WORK_ITEM = 1000;

    /** 未绑定提交的附件保留天数 */
    private static final int UNBOUND_RETENTION_DAYS = 7;

    /** 禁止上传的可执行、脚本与可内嵌脚本的格式 */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of("exe", "dll", "bat", "cmd", "com", "msi", "sh",
            "ps1", "vbs", "js", "mjs", "jar", "html", "htm", "xhtml", "svg", "php", "jsp", "asp", "aspx", "scr", "app");

    @Resource
    private FillAttachmentMapper attachmentMapper;

    @Resource
    private FillDraftRowMapper draftRowMapper;

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillSubmissionService submissionService;

    @Resource
    private ManagedFileApi storage;

    /**
     * 上传附件到指定组织实例的附件/图片字段。
     *
     * @param workItemId 工作项编号
     * @param fieldCode 字段码
     * @param file 文件
     * @return 附件
     */
    public FillAttachmentRespVO upload(Long workItemId, String fieldCode, MultipartFile file) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);
        String kind = fieldKind(item, fieldCode);
        if (!"ATTACHMENT".equals(kind) && !"IMAGE".equals(kind)) {
            throw exception(FILL_ATTACHMENT_INVALID, "该字段不是附件或图片字段");
        }
        if (file == null || file.isEmpty()) {
            throw exception(FILL_ATTACHMENT_INVALID, "文件为空");
        }
        long limit = "IMAGE".equals(kind) ? MAX_IMAGE_BYTES : MAX_ATTACHMENT_BYTES;
        if (file.getSize() > limit) {
            throw exception(FILL_ATTACHMENT_INVALID, "文件大小超过 " + (limit / 1024 / 1024) + "MB");
        }
        if (attachmentMapper.selectCount(FillAttachmentDO::getWorkItemId, workItemId) >= MAX_PER_WORK_ITEM) {
            throw exception(FILL_ATTACHMENT_INVALID, "本组织的附件数量已达上限");
        }
        String name = sanitizeName(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw exception(FILL_ATTACHMENT_INVALID, "文件读取失败");
        }
        if (bytes.length == 0 || bytes.length > limit) {
            throw exception(FILL_ATTACHMENT_INVALID, "文件大小不合法");
        }
        String contentType = "IMAGE".equals(kind) ? imageType(bytes) : attachmentType(name);
        if (contentType == null) {
            throw exception(FILL_ATTACHMENT_INVALID, "仅支持 PNG、JPEG、GIF、WEBP 图片");
        }

        // 1. 先落意图：跨服务超时、进程中断都能按操作键对账
        FillAttachmentDO attachment = FillAttachmentDO.builder()
                .workItemId(workItemId).fieldCode(fieldCode).name(name).contentType(contentType)
                .size((long) bytes.length).sha256(DigestUtil.sha256Hex(bytes))
                .operationKey("fillatt" + IdUtil.fastSimpleUUID())
                .state(FillAttachmentDO.STATE_PENDING).uploaderUserId(userId)
                .expiresAt(LocalDateTime.now().plusDays(UNBOUND_RETENTION_DAYS))
                .build();
        attachmentMapper.insert(attachment);
        // 2. 上传受管文件并核对回执
        ManagedFileCreateReqDTO request = new ManagedFileCreateReqDTO();
        request.setOperationKey(attachment.getOperationKey());
        request.setName(name);
        request.setContent(bytes);
        try {
            ManagedFileRespDTO receipt = storage.createManaged(request).getCheckedData();
            if (receipt == null || !"READY".equals(receipt.getState())
                    || !Objects.equals(receipt.getSize(), attachment.getSize())
                    || !Objects.equals(receipt.getSha256(), attachment.getSha256())) {
                throw exception(FILL_ATTACHMENT_STORAGE_UNAVAILABLE);
            }
            if (attachmentMapper.markReady(attachment.getId(), receipt.getId()) != 1) {
                throw exception(FILL_ATTACHMENT_STORAGE_UNAVAILABLE);
            }
        } catch (RuntimeException ex) {
            log.warn("[upload][附件({}) 上传未完成，留待清理任务对账：{}]", attachment.getId(), ex.getMessage());
            throw exception(FILL_ATTACHMENT_STORAGE_UNAVAILABLE);
        }
        FillAttachmentRespVO respVO = new FillAttachmentRespVO();
        respVO.setId(attachment.getId());
        respVO.setName(name);
        respVO.setContentType(contentType);
        respVO.setSize(attachment.getSize());
        return respVO;
    }

    /**
     * 下载或预览附件。
     *
     * @param id 附件编号
     * @param response 响应
     */
    public void download(Long id, HttpServletResponse response) throws IOException {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillAttachmentDO attachment = attachmentMapper.selectById(id);
        if (attachment == null || !FillAttachmentDO.STATE_READY.equals(attachment.getState())) {
            throw exception(FILL_ATTACHMENT_NOT_EXISTS);
        }
        FillWorkItemDO item = workItemService.getWorkItemValidated(attachment.getWorkItemId());
        if (!workItemService.hasViewPermission(item, userId)) {
            if (attachment.getSubmissionId() == null) {
                throw exception(FILL_WORK_ITEM_NOT_ASSIGNEE);
            }
            submissionService.checkSubmissionViewPermission(attachment.getSubmissionId(), userId);
        }
        byte[] content = storage.readManaged(attachment.getStorageFileId(), attachment.getOperationKey()).getCheckedData();
        if (content == null) {
            throw exception(FILL_ATTACHMENT_NOT_EXISTS);
        }
        boolean image = StrUtil.startWith(attachment.getContentType(), "image/");
        String encoded = URLEncoder.encode(attachment.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(image ? attachment.getContentType() : "application/octet-stream");
        response.setHeader("Content-Disposition", (image ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, no-store");
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
    }

    // ================================================================ 清理

    /**
     * 清理：长时间未完成的上传意图按操作键对账；过期且草稿不再引用的未绑定附件删除受管文件。
     *
     * @param limit 单轮处理上限
     * @return 清理数量
     */
    public int cleanup(int limit) {
        int cleaned = 0;
        LocalDateTime now = LocalDateTime.now();
        for (FillAttachmentDO pending : attachmentMapper.selectStalePending(now.minusHours(1), limit)) {
            try {
                ManagedFileRespDTO receipt = storage.getManaged(pending.getOperationKey()).getCheckedData();
                if (receipt == null) {
                    // 尚无回执不代表迟到写入不会发生：超过 24 小时仍无回执才放弃该意图
                    if (pending.getCreateTime() != null && pending.getCreateTime().isBefore(now.minusHours(24))
                            && attachmentMapper.markDeleted(pending.getId(), FillAttachmentDO.STATE_PENDING) == 1) {
                        cleaned++;
                    }
                    continue;
                }
                if (Boolean.TRUE.equals(storage.deleteManaged(receipt.getId(), pending.getOperationKey()).getCheckedData())
                        && attachmentMapper.markDeleted(pending.getId(), FillAttachmentDO.STATE_PENDING) == 1) {
                    cleaned++;
                }
            } catch (RuntimeException ex) {
                log.warn("[cleanup][附件意图({}) 对账失败，下一轮重试：{}]", pending.getId(), ex.getMessage());
            }
        }
        for (FillAttachmentDO expired : attachmentMapper.selectExpiredReady(now, limit)) {
            try {
                if (referencedByDraft(expired)) {
                    attachmentMapper.extendExpiry(expired.getId(), now.plusDays(UNBOUND_RETENTION_DAYS));
                    continue;
                }
                if (Boolean.TRUE.equals(storage.deleteManaged(expired.getStorageFileId(), expired.getOperationKey())
                        .getCheckedData())
                        && attachmentMapper.markDeleted(expired.getId(), FillAttachmentDO.STATE_READY) == 1) {
                    cleaned++;
                }
            } catch (RuntimeException ex) {
                log.warn("[cleanup][附件({}) 删除失败，下一轮重试：{}]", expired.getId(), ex.getMessage());
            }
        }
        return cleaned;
    }

    private boolean referencedByDraft(FillAttachmentDO attachment) {
        for (FillDraftRowDO row : draftRowMapper.selectListByWorkItemId(attachment.getWorkItemId())) {
            if (StrUtil.isBlank(row.getData())) {
                continue;
            }
            Map<?, ?> data = JsonUtils.parseObject(row.getData(), Map.class);
            if (data != null && FillControlService.parseIds(data.get(attachment.getFieldCode())).contains(attachment.getId())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ 内部

    private String fieldKind(FillWorkItemDO item, String fieldCode) {
        List<?> fields = JsonUtils.parseObject(tableVersionService.getTableVersion(item.getTableVersionId())
                .getFieldSchema(), List.class);
        if (fields != null) {
            for (Object field : fields) {
                if (field instanceof Map<?, ?> map && Objects.equals(String.valueOf(map.get("fieldCode")), fieldCode)) {
                    return map.get("refKind") == null ? null : String.valueOf(map.get("refKind"));
                }
            }
        }
        return null;
    }

    /** 去掉路径与控制字符，限制长度；禁止可执行与脚本类扩展名 */
    static String sanitizeName(String original) {
        String name = StrUtil.blankToDefault(original, "附件");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "附件";
        }
        if (name.length() > 200) {
            String extension = FileUtil.extName(name);
            name = name.substring(0, 190) + (StrUtil.isEmpty(extension) ? "" : "." + extension);
        }
        if (BLOCKED_EXTENSIONS.contains(StrUtil.nullToEmpty(FileUtil.extName(name)).toLowerCase())) {
            throw exception(FILL_ATTACHMENT_INVALID, "不支持上传该类型的文件");
        }
        return name;
    }

    /** 按文件头识别图片类型；不是支持的图片时返回 null */
    static String imageType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** 附件一律按二进制下载，记录常见类型仅用于展示 */
    private static String attachmentType(String name) {
        return switch (StrUtil.nullToEmpty(FileUtil.extName(name)).toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "doc", "docx" -> "application/msword";
            case "xls", "xlsx" -> "application/vnd.ms-excel";
            default -> "application/octet-stream";
        };
    }

}
