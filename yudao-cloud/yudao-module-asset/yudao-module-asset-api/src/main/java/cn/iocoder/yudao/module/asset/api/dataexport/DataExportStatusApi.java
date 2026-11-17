package cn.iocoder.yudao.module.asset.api.dataexport;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 仅通知资产回查已绑定实例；状态、原因和时间均来自 BPM。 */
@FeignClient(name = ApiConstants.NAME, contextId = "dataExportStatusApi")
public interface DataExportStatusApi {
    @PostMapping(ApiConstants.PREFIX + "/data-export/sync-status")
    CommonResult<Boolean> syncStatus(@RequestParam("exportId") Long exportId,
                                     @RequestParam("processInstanceId") String processInstanceId);
}
