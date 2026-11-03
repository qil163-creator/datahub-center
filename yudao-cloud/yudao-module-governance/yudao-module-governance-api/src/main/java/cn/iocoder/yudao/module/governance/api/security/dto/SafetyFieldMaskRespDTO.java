package cn.iocoder.yudao.module.governance.api.security.dto;

import java.util.*;

import lombok.Data;

@Data
public class SafetyFieldMaskRespDTO {

    private List<Map<String, Object>> rows;

    private String policyFingerprint;
}
