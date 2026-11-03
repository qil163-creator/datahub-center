package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "目录发布证据补齐评估；只读，不修改历史批准或授权")
public class AssetPublicationReadinessRespVO {

    private Long assetId;

    private Long catalogRevision;

    private boolean publishedEvidenceComplete;

    private long openApplications;

    private long pendingRevocations;

    private long unclosedSources;

    private List<String> missingItems;

    private List<String> steps;
}
