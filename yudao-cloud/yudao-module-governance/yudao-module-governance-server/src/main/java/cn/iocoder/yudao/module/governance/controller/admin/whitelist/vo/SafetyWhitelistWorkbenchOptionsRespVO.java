package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryRespVO;
import lombok.*;

import java.util.List;

@Data
public class SafetyWhitelistWorkbenchOptionsRespVO {

    private List<CategoryDataRespVO> directories;

    private List<Category> categories;

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Category extends SafetyCategoryRespVO {

        private Boolean whitelistConfigured;
    }
}
