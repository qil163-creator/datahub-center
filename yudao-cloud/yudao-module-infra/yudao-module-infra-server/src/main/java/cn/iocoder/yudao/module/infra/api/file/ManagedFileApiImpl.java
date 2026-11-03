package cn.iocoder.yudao.module.infra.api.file;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import cn.iocoder.yudao.module.infra.framework.security.ManagedFileRpcFilter;
import cn.iocoder.yudao.module.infra.service.file.FileService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class ManagedFileApiImpl implements ManagedFileApi {
    @Resource private FileService fileService;
    @Resource private HttpServletRequest request;

    @Override @SneakyThrows @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<ManagedFileRespDTO> createManaged(ManagedFileCreateReqDTO input) {
        return success(fileService.createManaged(ManagedFileRpcFilter.requireOwner(request), input));
    }
    @Override @SneakyThrows @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<ManagedFileRespDTO> getManaged(String operationKey) {
        return success(fileService.getManaged(ManagedFileRpcFilter.requireOwner(request), operationKey));
    }
    @Override @SneakyThrows @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<byte[]> readManaged(Long fileId, String operationKey) {
        return success(fileService.readManaged(ManagedFileRpcFilter.requireOwner(request), fileId, operationKey));
    }
    @Override @SneakyThrows @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO> readUserUpload(Long fileId, Long actorId) {
        ManagedFileRpcFilter.requireOwner(request);
        return success(fileService.readUserUpload(fileId, actorId));
    }
    @Override @SneakyThrows @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Boolean> deleteManaged(Long fileId, String operationKey) {
        fileService.deleteManaged(ManagedFileRpcFilter.requireOwner(request), fileId, operationKey);
        return success(true);
    }
}
