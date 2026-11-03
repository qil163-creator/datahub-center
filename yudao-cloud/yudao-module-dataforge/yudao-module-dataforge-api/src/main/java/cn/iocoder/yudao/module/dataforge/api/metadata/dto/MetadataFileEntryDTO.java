package cn.iocoder.yudao.module.dataforge.api.metadata.dto;

import lombok.Data;

@Data
public class MetadataFileEntryDTO {

    private String name;

    private String path;

    private boolean directory;

    private long size;

    private long modifiedTime;
}
