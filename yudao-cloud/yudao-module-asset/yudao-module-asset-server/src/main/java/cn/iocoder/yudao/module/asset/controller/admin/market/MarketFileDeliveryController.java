package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFileBindingRespVO;
import cn.iocoder.yudao.module.asset.service.market.MarketFileDeliveryService;
import cn.iocoder.yudao.module.asset.service.market.MarketFileDownloadService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Tag(name = "管理后台 - 市场文件交付")
@RestController
@RequestMapping("/asset/market")
@Validated
@RequiredArgsConstructor
public class MarketFileDeliveryController {
    private final MarketFileDeliveryService delivery;
    private final MarketFileDownloadService downloads;

    @Operation(summary = "查询指定目录版本的私有附件")
    @GetMapping("/file-delivery/binding")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<MarketFileBindingRespVO> binding(@RequestParam @Positive long assetId,
            @RequestParam @Positive long catalogVersionId) {
        return CommonResult.success(delivery.getBinding(assetId, catalogVersionId));
    }

    @Operation(summary = "绑定目录版本的私有附件")
    @PostMapping(value = "/file-delivery/binding", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    @LogRecord(type = "数据资产-市场文件", subType = "绑定交付文件", bizNo = "{{#assetId}}",
            success = "绑定目录版本【{{#catalogVersionId}}】的交付文件", extra = "BUSINESS_OPERATION")
    public CommonResult<MarketFileBindingRespVO> upload(@RequestParam @Positive long assetId,
            @RequestParam @Positive long catalogVersionId, @RequestPart("file") MultipartFile file) throws IOException {
        return CommonResult.success(delivery.upload(assetId, catalogVersionId, file));
    }

    @Operation(summary = "按本人的有效申请下载获批文件")
    @GetMapping("/my-assets/download")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    @LogRecord(type = "数据资产-市场文件", subType = "下载交付文件", bizNo = "{{#id}}",
            success = "下载申请【{{#id}}】的获批文件", extra = "BUSINESS_OPERATION")
    public void download(@RequestParam("id") @Positive long id, HttpServletResponse response) throws IOException {
        var file = downloads.download(id);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build().toString());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength(file.content().length);
        response.getOutputStream().write(file.content());
    }
}
