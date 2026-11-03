package cn.iocoder.yudao.module.infra.api.file;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import cn.iocoder.yudao.module.infra.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = ApiConstants.NAME, contextId = "managedFileApi", configuration = ManagedFileClientConfiguration.class)
@Tag(name = "RPC 服务 - 受管文件")
public interface ManagedFileApi {
    long MAX_USER_UPLOAD_BYTES = 50L * 1024 * 1024;
    String PREFIX = ApiConstants.PREFIX + "/file/managed";

    @PostMapping(PREFIX + "/create")
    @Operation(summary = "按稳定操作键上传有界文件")
    CommonResult<ManagedFileRespDTO> createManaged(@Valid @RequestBody ManagedFileCreateReqDTO request);

    @GetMapping(PREFIX + "/get")
    @Operation(summary = "按操作键查询并核对上传回执")
    CommonResult<ManagedFileRespDTO> getManaged(@NotBlank @RequestParam("operationKey") String operationKey);

    @GetMapping(PREFIX + "/content")
    @Operation(summary = "读取属于当前服务与操作键的文件，最大 20MB")
    CommonResult<byte[]> readManaged(@NotNull @RequestParam("fileId") Long fileId,
                                    @NotBlank @RequestParam("operationKey") String operationKey);

    /** actorId 必须由认证业务服务从当前登录上下文获得，不能接受浏览器提交的身份。 */
    @GetMapping(PREFIX + "/user-upload")
    @Operation(summary = "读取指定用户自己的普通上传，最大50MiB")
    CommonResult<cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO> readUserUpload(
            @NotNull @RequestParam("fileId") Long fileId,
            @NotNull @RequestParam("actorId") Long actorId);

    @DeleteMapping(PREFIX + "/delete")
    @Operation(summary = "可重放删除受管文件并保留操作墓碑")
    CommonResult<Boolean> deleteManaged(@NotNull @RequestParam("fileId") Long fileId,
                                       @NotBlank @RequestParam("operationKey") String operationKey);
}
