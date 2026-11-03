package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import lombok.Data;

@Data
public class FileResourceSummaryRespVO {

    private String connectionStatus;

    private Long fileCount;

    private Long todayUploadCount;

    private Long directoryCount;

    private Long storageBytes;

    private boolean complete;

    private String message;
}
