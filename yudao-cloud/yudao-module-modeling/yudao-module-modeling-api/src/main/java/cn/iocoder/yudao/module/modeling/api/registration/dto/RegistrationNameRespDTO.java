package cn.iocoder.yudao.module.modeling.api.registration.dto;

import lombok.Data;

@Data
public class RegistrationNameRespDTO {

    private String assetName;

    private String businessEnglishName;

    private Long dataDomainId;

    private String ruleType;
}
