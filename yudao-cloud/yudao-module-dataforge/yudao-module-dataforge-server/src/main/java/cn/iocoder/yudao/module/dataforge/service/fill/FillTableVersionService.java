package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTableVersionPublishReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;

import java.util.List;

/**
 * 在线填报-模板版本 Service 接口
 *
 * @author Li
 */
public interface FillTableVersionService {

    /**
     * 从填报表当前字段配置固化一个不可变模板版本
     *
     * 固化内容：字段结构（fieldCode/类型/字典/必填）、目标映射（数据源/表/列映射）、
     * 基础校验规则、合并策略与业务键、内容哈希；发布后模板进入审批流工作模式
     *
     * @return 版本编号
     */
    Long publishVersion(FillTableVersionPublishReqVO reqVO);

    /**
     * 获得模板版本（不存在抛异常）
     */
    FillTableVersionDO getTableVersion(Long id);

    /**
     * 获得模板版本，并校验可被新任务引用（不存在或已停用抛异常）
     */
    FillTableVersionDO getActiveTableVersion(Long id);

    /**
     * 获得填报表的版本列表（按版本号倒序）
     */
    List<FillTableVersionDO> getTableVersionListByTableId(Long tableId);

    /**
     * 停用模板版本（仅禁止新任务引用，不影响进行中任务）
     */
    void disableTableVersion(Long id);

}
