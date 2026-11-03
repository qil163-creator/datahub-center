package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevDirDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据开发-任务目录 Mapper
 *
 * @author Li
 */
@Mapper
public interface DevDirMapper extends BaseMapperX<DevDirDO> {

    /** 开发类目写入共用事务锁，协调工作台与基础管理的树修改。 */
    @Select("SELECT true FROM (SELECT pg_advisory_xact_lock(216031)) tree_lock")
    boolean lockTree();

    /**
     * 查询列表全部。
     *
     * @return 列表全部结果集合
     */
    default List<DevDirDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<DevDirDO>()
                .orderByAsc(DevDirDO::getSort)
                .orderByAsc(DevDirDO::getId));
    }

    /**
     * 按父级编号查询开发目录数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(DevDirDO::getParentId, parentId);
    }

}
