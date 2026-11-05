package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo.*;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 外部文件管理")
@RestController
@RequestMapping("/dataforge/file-resource")
@Validated
public class FileResourceController {

    @Resource
    private FileResourceService service;

    /**
     * 获得可管理的文件连接。
     *
     * @return 可管理的文件连接结果集合
     */
    @GetMapping("/connections")
    @Operation(summary = "获得可管理的文件连接")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    public CommonResult<List<FileResourceConnectionRespVO>> connections() {
        return success(service.connections());
    }

    /**
     * 统计连接根目录文件。
     *
     * @param connectionId 连接编号
     * @return 文件资源汇总响应数据
     */
    @GetMapping("/summary")
    @Operation(summary = "统计连接根目录文件")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    public CommonResult<FileResourceSummaryRespVO> summary(@RequestParam @NotNull Long connectionId) {
        return success(service.summary(connectionId));
    }

    /**
     * 按需加载子目录。
     *
     * @param req 请求参数
     * @return 按需加载子目录结果集合
     */
    @GetMapping("/tree")
    @Operation(summary = "按需加载子目录")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    public CommonResult<List<FileResourceEntryRespVO>> tree(@Valid FileResourcePathReqVO req) {
        return success(service.tree(req.getConnectionId(), req.getPath()));
    }

    /**
     * 分页筛选当前目录。
     *
     * @param req 请求参数
     * @return 分页筛选当前目录结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页筛选当前目录")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    public CommonResult<PageResult<FileResourceEntryRespVO>> page(@Valid FileResourcePageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 受限文本预览。
     *
     * @param req 请求参数
     * @return 文件资源预览响应数据
     */
    @GetMapping("/preview")
    @Operation(summary = "受限文本预览")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<FileResourcePreviewRespVO> preview(@Valid FileResourcePathReqVO req) {
        return success(service.preview(req.getConnectionId(), req.getPath()));
    }

    /**
     * 读取图像或 PDF 预览内容。
     *
     * @param req 请求参数
     * @param response 响应对象
     */
    @GetMapping("/preview-content")
    @Operation(summary = "读取图像或 PDF 预览内容")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public void previewContent(@Valid FileResourcePathReqVO req, HttpServletResponse response) throws IOException {
        FileResourceService.PreviewContent content = service.previewContent(req.getConnectionId(), req.getPath());
        response.setContentType(content.mime());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        response.setHeader("Content-Disposition",
                ContentDisposition.attachment().filename(content.name(), StandardCharsets.UTF_8).build().toString());
        response.setContentLength(content.content().length);
        response.getOutputStream().write(content.content());
    }

    /**
     * 上传文件到选定连接目录。
     *
     * @param connectionId 连接编号
     * @param path 路径
     * @param file 文件
     * @param overwrite 覆盖
     * @return 文件资源
     */
    @PostMapping("/upload")
    @LogRecord(type = "数据开发-外部文件管理", subType = "上传文件到选定连接目录", bizNo = "{{(#connectionId) ?: 0}}",
            success = "上传文件到选定连接目录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "上传文件到选定连接目录")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:create')")
    @ApiAccessLog(requestEnable = false)
    public CommonResult<Boolean> upload(@RequestParam Long connectionId, @RequestParam String path,
            @RequestParam MultipartFile file, @RequestParam(defaultValue = "false") boolean overwrite) {
        service.upload(connectionId, path, file, overwrite);
        return success(true);
    }

    /**
     * 创建远程文件夹。
     *
     * @param req 请求参数
     * @return 文件资源
     */
    @PostMapping("/mkdir")
    @LogRecord(type = "数据开发-外部文件管理", subType = "创建远程文件夹", bizNo = "{{(#req.connectionId) ?: 0}}",
            success = "创建远程文件夹", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建远程文件夹")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:create')")
    public CommonResult<Boolean> mkdir(@Valid @RequestBody FileResourceNameReqVO req) {
        service.mkdir(req);
        return success(true);
    }

    /**
     * 重命名远程文件或目录。
     *
     * @param req 请求参数
     * @return 文件资源
     */
    @PutMapping("/rename")
    @LogRecord(type = "数据开发-外部文件管理", subType = "重命名远程文件或目录", bizNo = "{{(#req.connectionId) ?: 0}}",
            success = "重命名远程文件或目录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "重命名远程文件或目录")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:update')")
    public CommonResult<Boolean> rename(@Valid @RequestBody FileResourceNameReqVO req) {
        service.rename(req);
        return success(true);
    }

    /**
     * 逐项删除远程文件或目录。
     *
     * @param req 请求参数
     * @return 文件资源批次响应数据
     */
    @PostMapping("/delete")
    @LogRecord(type = "数据开发-外部文件管理", subType = "执行远程文件批量删除（逐项结果见业务回执）", bizNo = "{{(#req.connectionId) ?: 0}}",
            success = "执行远程文件批量删除（逐项结果见业务回执）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "逐项删除远程文件或目录")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:delete')")
    public CommonResult<FileResourceBatchRespVO> delete(@Valid @RequestBody FileResourceBatchReqVO req) {
        return success(service.delete(req));
    }

    /**
     * 下载文件或目录压缩包。
     *
     * @param req 请求参数
     * @param response 响应对象
     */
    @GetMapping("/download")
    @Operation(summary = "下载文件或目录压缩包")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:download')")
    @ApiAccessLog(operateType = OperateTypeEnum.EXPORT, requestEnable = false)
    public void download(@Valid FileResourcePathReqVO req, HttpServletResponse response) throws IOException {
        send(req.getConnectionId(), List.of(req.getPath()), false, response);
    }

    /**
     * 下载选中文件与目录压缩包。
     *
     * @param req 请求参数
     * @param response 响应对象
     */
    @PostMapping("/download-batch")
    @Operation(summary = "下载选中文件与目录压缩包")
    @PreAuthorize("@ss.hasPermission('dataforge:data-file:download')")
    @ApiAccessLog(operateType = OperateTypeEnum.EXPORT, requestEnable = false)
    public void downloadBatch(@Valid @RequestBody FileResourceBatchReqVO req,
            HttpServletResponse response) throws IOException {
        send(req.getConnectionId(), req.getPaths(), true, response);
    }

    /**
     * 发送文件资源消息。
     *
     * @param id 记录编号
     * @param paths 路径
     * @param batch 目标批次信息
     * @param response 响应对象
     */
    private void send(Long id, List<String> paths, boolean batch, HttpServletResponse response) throws IOException {
        Path temporary = Files.createTempFile("dataforge-file-download-", ".tmp");
        try {
            String name;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                name = service.download(id, paths, output, batch);
            }
            response.setContentType("application/octet-stream");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Content-Disposition",
                    ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(Files.size(temporary));
            Files.copy(temporary, response.getOutputStream());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

