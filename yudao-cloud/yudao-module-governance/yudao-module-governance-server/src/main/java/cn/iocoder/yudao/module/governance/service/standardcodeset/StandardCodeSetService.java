package cn.iocoder.yudao.module.governance.service.standardcodeset;

import java.util.*;

import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetSaveReqVO;
import jakarta.validation.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;

/**
 * 数据标准--标准码 Service 接口
 *
 * @author Li
 */
public interface StandardCodeSetService {

    /**
     * 切换标准启用状态。
     */
    void updateStatus(Integer id, Short status, Long revision);

    /**
     * 创建数据标准--标准码
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Integer createStandardCodeSet(@Valid StandardCodeSetSaveReqVO createReqVO);

    /**
     * 更新数据标准--标准码
     *
     * @param updateReqVO 更新信息
     */
    void updateStandardCodeSet(@Valid StandardCodeSetSaveReqVO updateReqVO);

    /**
     * 删除数据标准--标准码
     *
     * @param id 编号
     */
    void deleteStandardCodeSet(Integer id);

    /**
     * 批量删除数据标准--标准码
     *
     * @param ids 编号
     */
    void deleteStandardCodeSetListByIds(List<Integer> ids);

    /**
     * 获得数据标准--标准码
     *
     * @param id 编号
     * @return 数据标准--标准码
     */
    StandardCodeSetDO getStandardCodeSet(Integer id);

    /**
     * 获得数据标准--标准码分页
     *
     * @param pageReqVO 分页查询
     * @return 数据标准--标准码分页
     */
    PageResult<StandardCodeSetDO> getStandardCodeSetPage(StandardCodeSetPageReqVO pageReqVO);

    /**
     * 填充码表下的码值条目数量
     *
     * @param list 码表列表
     */
    void fillItemCounts(List<StandardCodeSetRespVO> list);

    /**
     * 批量导入码表与码值
     *
     * @param importList    导入行列表（每行一个码值，按字典类型归组）
     * @param updateSupport 是否支持更新（按字典类型 + 键值匹配已有码值）
     * @return 导入结果
     */
    StandardCodeSetImportRespVO importStandardCodeSetList(List<StandardCodeSetImportExcelVO> importList,
            boolean updateSupport);

}
