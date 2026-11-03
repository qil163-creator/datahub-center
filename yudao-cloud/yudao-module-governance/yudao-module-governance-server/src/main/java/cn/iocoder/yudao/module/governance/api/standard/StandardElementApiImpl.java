package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardElementRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class StandardElementApiImpl implements StandardElementApi {

    @Resource
    private StandardElementMapper standardElementMapper;

    /**
     * 按编号批量获得标准数据元精简信息。
     */
    @Override
    public CommonResult<List<StandardElementRespDTO>> getElementListByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return success(List.of());
        }
        List<StandardElementRespDTO> result = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id <= 0 || id > Integer.MAX_VALUE) {
                continue;
            }
            StandardElementDO head = standardElementMapper.selectById(id.intValue());
            if (head == null) {
                continue;
            }
            result.add(BeanUtils.toBean(head, StandardElementRespDTO.class));
        }
        return success(result);
    }

}
