package cn.iocoder.yudao.module.system.api.user.dto;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Size;
import lombok.*;
@Data @EqualsAndHashCode(callSuper=true)
public class AdminUserOptionPageReqDTO extends PageParam {
 @Override @jakarta.validation.constraints.Max(100) @jakarta.validation.constraints.Min(1) public Integer getPageSize(){return super.getPageSize();}
 @Size(max=30) private String username;
 @Size(max=32) private String mobile;
}
