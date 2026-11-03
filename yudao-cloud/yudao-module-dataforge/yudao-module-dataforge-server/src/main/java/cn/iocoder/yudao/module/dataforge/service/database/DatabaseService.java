package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据源中心--数据库信息 Service 接口
 *
 * @author Li
 */
public interface DatabaseService {

    /**
     * 创建数据源中心--数据库信息
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDatabase(@Valid DatabaseSaveReqVO createReqVO);

    /**
     * 更新数据源中心--数据库信息
     *
     * @param updateReqVO 更新信息
     */
    void updateDatabase(@Valid DatabaseSaveReqVO updateReqVO);

    /**
     * 删除数据源中心--数据库信息
     *
     * @param id 编号
     */
    void deleteDatabase(Long id);

    /**
     * 批量删除数据源中心--数据库信息
     *
     * @param ids 编号
     */
    void deleteDatabaseListByIds(List<Long> ids);

    /**
     * 获得数据源中心--数据库信息
     *
     * @param id 编号
     * @return 数据源中心--数据库信息
     */
    DatabaseDO getDatabase(Long id);

    /**
     * 获得数据源中心--数据库信息分页
     *
     * @param pageReqVO 分页查询
     * @return 数据源中心--数据库信息分页
     */
    PageResult<DatabaseDO> getDatabasePage(DatabasePageReqVO pageReqVO);

    /**
     * 填充类型名称、图标、项目名称、单位名称
     *
     * @param list 数据库信息
     * @return 展示 VO
     */
    List<DatabaseRespVO> convertToRespList(List<DatabaseDO> list);

    /**
     * 测试数据库连接
     *
     * @param id 编号
     */
    void testDatabase(Long id);

    /**
     * 按表单内容测试数据库连接
     *
     * @param reqVO 连接信息
     */
    void testDatabase(DatabaseSaveReqVO reqVO);

    /** 使用组件执行真实分组诊断；写入检查需要额外更新权限。 */
    DatabaseDiagnosisRespVO diagnoseDatabase(@Valid DatabaseDiagnosisReqVO reqVO);

    /**
     * 获得数据源精简列表（启用状态），可按库用途过滤
     *
     * @param usageType 库用途，空表示全部
     * @return 精简列表
     */
    List<DatabaseSimpleRespVO> getSimpleDatabaseList(Integer usageType);

    /**
     * 获得数据源下的表清单（含视图标记）
     *
     * @param id 数据源编号
     * @return 表清单
     */
    List<DatabaseTableRespVO> getTableList(Long id);

    /**
     * 获得数据源下指定表的属性与字段结构
     *
     * @param id        数据源编号
     * @param tableName 表名
     * @return 表属性
     */
    DatabaseTableMetaRespVO getTableMeta(Long id, String tableName);

    /**
     * 只读预览指定表样例数据
     *
     * @param reqVO 预览参数
     * @return 样例数据
     */
    DatabasePreviewRespVO previewTable(DatabasePreviewReqVO reqVO);

}
