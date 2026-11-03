package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlFileOutputRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlFileRetryReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlFileRetireReqVO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlFileRecoveryService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlFileDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_FILE_STATE_CHANGED;

@RestController
@RequestMapping("/dataforge/etl-file-output")
@Tag(name = "管理后台 - ETL 文件输出对账")
@Validated
public class EtlFileOutputController {

    @Resource
    private EtlFileRecoveryService service;

    @Resource
    private EtlFileDownloadService downloads;

    /**
     * 获取服务端允许的逻辑存储键，不返回物理路径。
     *
     * @return 服务端允许的逻辑存储键，不返回物理路径结果集合
     */
    @GetMapping("/storages")
    @Operation(summary = "获取服务端允许的逻辑存储键，不返回物理路径")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query') && @ss.hasPermission('dataforge:etl:run')")
    public CommonResult<List<EtlFileRecoveryService.Storage>> storages() {
        return success(service.storages());
    }

    /**
     * 查看当前平台内本人运行的文件回执，保留已删除运行的恢复记录。
     *
     * @param recordId 记录编号
     * @return 查看当前平台内本人运行的文件回执，保留已删除运行的恢复记录结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "查看当前平台内本人运行的文件回执，保留已删除运行的恢复记录")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlFileOutputRespVO>> list(@RequestParam("recordId") @Positive Long recordId) {
        return success(service.list(recordId));
    }

    /**
     * 下载本人成功运行已封存发布的文件，仅接受文件台账编号。
     *
     * @param id 记录编号
     * @param response 响应对象
     */
    @GetMapping("/download")
    @Operation(summary = "下载本人成功运行已封存发布的文件，仅接受文件台账编号")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(
            operateType = cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT,
            requestEnable = false, responseEnable = false)
    public void download(@RequestParam("id") @Positive Long id, HttpServletResponse response) throws IOException {
        boolean transferring = false;
        try (var download = downloads.open(id)) {
            download.content().verifyUnchanged();
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(download.content().bytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            transferring = true;
            download.content().transferTo(response.getOutputStream());
        } catch (IOException failure) {
            if (!transferring) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            // 流式传输开始后，客户端断连及意外的存储变化无法再转换为 JSON 响应。
            // 对外记录传输失败时，禁止附带含本地路径的文件系统异常原因。
            throw new IOException("ETL 文件传输未完成");
        }
    }

    /**
     * 按修订号重试本人已失去租约的文件对账，不强制覆盖或删除文件。
     *
     * @param request 请求参数
     * @return ETL 文件输出
     */
    @PostMapping("/retry")
    @Operation(summary = "按修订号重试本人已失去租约的文件对账，不强制覆盖或删除文件")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query') && (@ss.hasPermission('dataforge:etl:run') || @ss.hasPermission('dataforge:etl:file-cleanup'))")
    public CommonResult<Boolean> retry(@Valid @RequestBody EtlFileRetryReqVO request) {
        service.retry(request);
        return success(true);
    }

    /**
     * 明确确认后申请清理本人已发布文件，后台在到期且身份可证明时执行。
     *
     * @param request 请求参数
     * @return ETL 文件输出响应数据
     */
    @PostMapping("/retire")
    @Operation(summary = "明确确认后申请清理本人已发布文件，后台在到期且身份可证明时执行")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query') && @ss.hasPermission('dataforge:etl:file-cleanup')")
    public CommonResult<EtlFileOutputRespVO> retire(@Valid @RequestBody EtlFileRetireReqVO request) {
        return success(service.requestRetirement(request));
    }
}
