package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数仓分层 Mapper
 *
 * @author Li
 */
@Mapper
public interface DwLayerMapper extends BaseMapperX<DwLayerDO> {

    /**
     * 按查询条件读取数仓分层列表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件读取数仓分层列表结果集合
     */
    default List<DwLayerDO> selectList(DwLayerListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<DwLayerDO>()
                .likeIfPresent(DwLayerDO::getLayerName, reqVO.getLayerName())
                .eqIfPresent(DwLayerDO::getIsBuiltin, reqVO.getIsBuiltin())
                .eqIfPresent(DwLayerDO::getStatus, reqVO.getStatus())
                .orderByAsc(DwLayerDO::getSortOrder)
                .orderByAsc(DwLayerDO::getId));
    }

    /**
     * 按父级编号与分层名称查询数仓分层。
     *
     * @param parentId 父级编号
     * @param layerName 分层名称
     * @return 数仓分层
     */
    default DwLayerDO selectByParentIdAndLayerName(Long parentId, String layerName) {
        // 用 selectFirstOne（内部 selectList）避开 selectOne 的 Cursor 路径，PG 下会报 statement 已关闭
        return selectFirstOne(DwLayerDO::getParentId, parentId, DwLayerDO::getLayerName, layerName);
    }

    /**
     * 按分层编码查询数仓分层。
     *
     * @param layerCode 分层编码
     * @return 数仓分层
     */
    default DwLayerDO selectByLayerCode(String layerCode) {
        return selectFirstOne(DwLayerDO::getLayerCode, layerCode);
    }

    /**
     * 清理分层仅字段。
     *
     * @param id 记录编号
     */
    default void clearLayerOnlyFields(Long id) {
        update(null, new LambdaUpdateWrapper<DwLayerDO>()
                .eq(DwLayerDO::getId, id)
                .set(DwLayerDO::getLayerCode, null)
                .set(DwLayerDO::getTablePrefix, null)
                .set(DwLayerDO::getDefaultTableType, null));
    }

    /**
     * 按父级编号查询数仓分层数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(DwLayerDO::getParentId, parentId);
    }

}
