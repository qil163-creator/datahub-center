package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryDatasourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;

import java.util.List;

/**
 * 数据查询工作台 Service：自助式多源数据探索，
 * 数据源、表、字段与执行能力均由 dataforge 数据源中心透出，本模块不落库。
 */
public interface DataQueryService {

    /**
     * 获得可查询的数据源列表
     *
     * @return 数据源列表
     */
    List<DataQueryDatasourceRespVO> getDatasourceList();

    /**
     * 获得数据源下的表名列表
     *
     * @param datasourceId 数据源编号
     * @return 表名列表
     */
    List<String> getTableList(Long datasourceId);

    /**
     * 获得数据源下指定表的字段列表
     *
     * @param datasourceId 数据源编号
     * @param tableName    表名
     * @return 字段列表
     */
    List<DataQueryColumnRespVO> getColumnList(Long datasourceId, String tableName);

    /**
     * 只读执行单条 SELECT 查询
     *
     * @param reqVO 查询请求
     * @return 查询结果
     */
    DataQueryResultRespVO executeQuery(DataQueryExecuteReqVO reqVO);

    /**
     * 为已批准的导出重新执行查询（访问校验不变，按 DATA_EXPORT 场景脱敏）。
     */
    DataQueryResultRespVO executeForExport(DataQueryExecuteReqVO reqVO);

    /**
     * 导出申请前的访问校验，不读取数据。
     *
     * @return [schema, table]
     */
    String[] checkExportable(DataQueryExecuteReqVO reqVO);

}
