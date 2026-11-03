package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelRespVO;
import lombok.Data;

import java.util.List;

@Data
public class SafetyCategoryOptionsRespVO {

    private List<CategoryDataRespVO> directories;

    private List<SafetyLevelRespVO> levels;

    private List<Priority> priorities;

    @Data
    public static class Priority {

        private String value;

        private String label;
    }
}
