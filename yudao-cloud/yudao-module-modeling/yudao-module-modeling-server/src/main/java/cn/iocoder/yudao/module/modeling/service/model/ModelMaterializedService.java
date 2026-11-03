package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.*;

import java.util.List;

public interface ModelMaterializedService {

    /**
     * 物化发布分页。
     *
     * @param reqVO 请求参数
     * @return 物化发布分页结果
     */
    PageResult<ModelMaterializedRespVO> getPage(ModelMaterializedPageReqVO reqVO);

    /**
     * 读取模型物化响应数据。
     *
     * @param id 记录编号
     * @return 模型物化响应数据
     */
    ModelMaterializedRespVO get(Long id);

    /**
     * 按模型编号读取模型物化。
     *
     * @param modelId 模型编号
     * @return 模型物化响应数据
     */
    ModelMaterializedRespVO getByModelId(Long modelId);

    /**
     * 获得模型发布数据源选项（不含凭据）。
     *
     * @return 数据源列表
     */
    List<ModelMaterializedDatabaseRespVO> getDatabaseList();

    /**
     * 获得发布模型分类目录。
     *
     * @return 分类树
     */
    List<ModelMaterializedCatNodeRespVO> getCatTree();

    /**
     * 发布日志分页。
     *
     * @param reqVO 请求参数
     * @return 发布日志分页结果
     */
    PageResult<ModelMaterializedLogRespVO> getLogPage(ModelMaterializedLogPageReqVO reqVO);

    /**
     * 首次发布模型。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    Long publish(ModelMaterializedPublishReqVO reqVO);

    /**
     * 重新发布模型。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    Long republish(ModelMaterializedRepublishReqVO reqVO);

    /**
     * 对账未知部署或恢复已过期执行；不执行新的DDL。
     *
     * @param id 记录编号
     * @return 对账未知部署或恢复已过期执行；不执行新的DDL文本
     */
    String reconcile(Long id);

}
