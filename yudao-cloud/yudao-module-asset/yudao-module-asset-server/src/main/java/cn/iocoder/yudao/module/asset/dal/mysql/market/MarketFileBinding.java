package cn.iocoder.yudao.module.asset.dal.mysql.market;

import lombok.Data;

/** 对应一个目录版本的不可变文件身份；内部存储标识不向浏览器返回。 */
@Data
public class MarketFileBinding {
    private Long catalogVersionId;
    private Long assetId;
    private String operationKey;
    private String fileName;
    private Long fileSize;
    private String sha256;
    private Long managedFileId;
    private Long uploaderId;
}
