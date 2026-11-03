package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import lombok.Data;

import java.util.List;

@Data
public class SafetyRuleWorkbenchOptionsRespVO {

    private List<CategoryDataRespVO> directories;

    private List<SafetyCategoryRespVO> categories;

    private List<Option> applicationScenes;

    private List<Option> maskTypes;

    private List<Option> replaceRules;

    @Data
    public static class Option {

        private String value;

        private String label;

        private Boolean disabled;
    }
}
