# 数据建模统一操作日志接入清单

本清单记录管理端关键业务操作的系统操作日志接入。复用 framework 的 `BusinessOperateLog` 与原有系统日志写入链路；不改变接口权限、对象范围和领域独立审计。

新增、修改、删除、状态变更、发布、授权、审批和执行等入口在成功返回且事务提交后记录。日志只提取声明的业务编号，不保存请求体、SQL、密钥、签名、审批正文或响应载荷。日志写入失败不回滚已完成业务，保留可定位的服务告警。

只读查询、只读执行、预览、校验和下载导出不新增领域操作日志。既有领域路径已写入系统日志时保留原记录，避免重复。

本次新增注解：49 个。

| 控制器 | 接口 | 动作 | 业务编号表达式 / 处理说明 |
|---|---|---|---|
| `BusinessConditionController` | `POST /create` | 创建业务限定定义 | `#result` |
| `BusinessConditionController` | `PUT /update` | 修改未启用的业务限定 | `#request.id` |
| `BusinessConditionController` | `DELETE /delete` | 删除未启用的业务限定 | `#id` |
| `BusinessConditionController` | `PUT /update-status` | 启用或停用业务限定 | `#request.id` |
| `BusinessConditionController` | `GET /get` | 获得业务限定详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessConditionController` | `GET /page` | 分页查询业务限定 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessConditionController` | `GET /source-fields` | 获得来源已发布当前设计的稳定字段 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessEntityController` | `POST /create` | 新增业务实体 | `#result` |
| `BusinessEntityController` | `PUT /update` | 修改业务实体及关系 | `#request.id` |
| `BusinessEntityController` | `PUT /update-status` | 上线或下线业务实体 | 既有领域 auditStatus 已汇聚系统操作日志 |
| `BusinessEntityController` | `PUT /update-status-batch` | 原子批量上线或下线业务实体 | 既有领域 auditStatus 已逐实体汇聚系统操作日志 |
| `BusinessEntityController` | `DELETE /delete` | 删除业务实体 | `#id` |
| `BusinessEntityController` | `DELETE /delete-list` | 原子批量删除业务实体 | `#request.targets.![id]` |
| `BusinessEntityController` | `GET /get` | 获得业务实体及其关系 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessEntityController` | `GET /page` | 获得业务实体分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessEntityController` | `GET /tree-page` | 获得业务实体包含关系树分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `BusinessEntityController` | `GET /options` | 获得板块实体及流程占用约束 | 只读查询、预览或导出，不新增业务操作日志 |
| `ConceptModelController` | `POST /create` | 创建概念模型草稿 | `#result` |
| `ConceptModelController` | `PUT /update` | 修改概念模型及实体关系设计 | `#request.id` |
| `ConceptModelController` | `PUT /update-status` | 启用或停用概念模型 | `#request.id` |
| `ConceptModelController` | `DELETE /delete` | 删除概念模型草稿 | `#id` |
| `ConceptModelController` | `GET /get` | 获得概念模型及完整实体关系设计 | 只读查询、预览或导出，不新增业务操作日志 |
| `ConceptModelController` | `GET /page` | 获得概念模型分页，不返回设计正文 | 只读查询、预览或导出，不新增业务操作日志 |
| `IndicatorModelController` | `POST /create` | 创建指标定义 | `#result` |
| `IndicatorModelController` | `PUT /update` | 更新草稿指标定义 | `#request.id` |
| `IndicatorModelController` | `DELETE /delete` | 删除未被引用的草稿指标 | `#id` |
| `IndicatorModelController` | `PUT /update-status` | 启用或停用指标定义 | `#request.id` |
| `IndicatorModelController` | `GET /get` | 获得指标定义详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `IndicatorModelController` | `GET /page` | 分页查询指标定义 | 只读查询、预览或导出，不新增业务操作日志 |
| `IndicatorModelController` | `GET /atomic-options` | 获得可引用的启用原子指标 | 只读查询、预览或导出，不新增业务操作日志 |
| `IndicatorModelController` | `GET /source-fields` | 获得来源已发布当前设计的稳定字段 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicTableController` | `GET /page` | 获得逻辑表分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicTableController` | `GET /cat-tree` | 获得逻辑表类目树 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicTableController` | `GET /source-system/list` | 获得启用的来源系统 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicTableController` | `PUT /update-enabled` | 启用或停用逻辑表 | `#id` |
| `LogicTableController` | `GET /delete-check` | 批量删除前检查可删除的逻辑表 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicTableController` | `DELETE /delete` | 删除逻辑表（支持批量） | `#ids` |
| `LogicModelController` | `POST /create` | 创建逻辑模型（正向/逆向建模） | `#result` |
| `LogicModelController` | `PUT /update` | 修改逻辑模型基础信息 | `#updateReqVO.id` |
| `LogicModelController` | `PUT /update-enabled` | 启用或禁用逻辑模型 | `#id` |
| `LogicModelController` | `PUT /draft-save` | 原子保存统一模型草稿、字段与业务口径 | `#reqVO.modelId` |
| `LogicModelController` | `DELETE /delete` | 删除逻辑模型 | `#id` |
| `LogicModelController` | `GET /get` | 获得逻辑模型 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `POST /summary-preview` | 预览汇总逻辑表的时间、粒度和派生指标字段 | 只读汇总字段预览 |
| `LogicModelController` | `GET /page` | 逻辑模型分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `GET /design-get` | 获得模型当前设计 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `PUT /publish` | 发布模型当前设计 | `#modelId` |
| `LogicModelController` | `PUT /discard` | 废弃逻辑模型 | `#id` |
| `LogicModelController` | `GET /impact` | 影响分析（设计关系、部署目标） | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `GET /er-diagram` | 获得主题 ER 图（模型 + 当前字段 + 关系） | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `POST /relation-save` | 新增/修改逻辑模型关系 | `#result` |
| `LogicModelController` | `DELETE /relation-delete` | 删除逻辑模型关系 | `#id` |
| `LogicModelController` | `PUT /er-layout-save` | 批量保存模型 ER 画布坐标 | `#reqVO.positions.![modelId]` |
| `LogicModelController` | `GET /database-list` | 数据源精简列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `GET /table-list` | 数据源下的表名列表（逆向建模选用） | 只读查询、预览或导出，不新增业务操作日志 |
| `LogicModelController` | `GET /import-fields` | 从数据源表拉取字段（逻辑模型建表前预览） | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelIntegrityController` | `GET /check` | 只读完整性检测（不定主域、不改模型） | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `GET /page` | 物化发布分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `GET /get` | 发布模型详情（按模型编号可查看尚未物化的模型） | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `GET /database-list` | 获得模型发布数据源选项（不含凭据） | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `GET /cat-tree` | 获得发布模型分类目录 | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `GET /log-page` | 发布日志分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `ModelMaterializedController` | `POST /publish` | 首次发布模型 | `#reqVO.modelId` |
| `ModelMaterializedController` | `POST /republish` | 重新发布模型 | `#result` |
| `ModelMaterializedController` | `POST /reconcile` | 对账未知部署或恢复已过期执行；不执行新的DDL | `#id` |
| `ModelOverviewController` | `GET /get` | 获得数据建模总览统计（首页数据概览） | 只读查询、预览或导出，不新增业务操作日志 |
| `BizCategoryController` | `POST /create` | 创建业务分类 | `#result` |
| `BizCategoryController` | `PUT /update` | 更新业务分类 | `#updateReqVO.id` |
| `BizCategoryController` | `PUT /update-status` | 更新业务分类状态 | `#reqVO.id` |
| `BizCategoryController` | `DELETE /delete` | 删除业务分类 | `#id` |
| `BizCategoryController` | `GET /get` | 获得业务分类 | 只读查询、预览或导出，不新增业务操作日志 |
| `BizCategoryController` | `GET /list` | 获得业务分类列表（树形数据平铺返回） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataDomainController` | `POST /create` | 创建数据板块 | `#result` |
| `DataDomainController` | `PUT /update` | 更新数据板块 | `#updateReqVO.id` |
| `DataDomainController` | `PUT /update-status` | 更新数据板块状态 | `#reqVO.id` |
| `DataDomainController` | `DELETE /delete` | 删除数据板块 | `#id` |
| `DataDomainController` | `PUT /update-naming-rules` | 更新板块六类逻辑表命名规则 | `#request.id` |
| `DataDomainController` | `DELETE /delete-list` | 批量删除数据板块 | `#ids` |
| `DataDomainController` | `GET /get` | 获得数据板块 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataDomainController` | `GET /page` | 获得数据板块分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataDomainController` | `GET /simple-list` | 获得数据板块全量列表（下拉选择用） | 只读查询、预览或导出，不新增业务操作日志 |
| `DwLayerController` | `POST /create` | 创建数仓分层 | `#result` |
| `DwLayerController` | `PUT /update` | 更新数仓分层 | `#updateReqVO.id` |
| `DwLayerController` | `PUT /update-status` | 更新数仓分层状态 | `#reqVO.id` |
| `DwLayerController` | `DELETE /delete` | 删除数仓分层 | `#id` |
| `DwLayerController` | `GET /get` | 获得数仓分层 | 只读查询、预览或导出，不新增业务操作日志 |
| `DwLayerController` | `GET /prefix-preview` | 预览修改表前缀后的候选表名与差异，只读，不重命名已有物理表 | 只读查询、预览或导出，不新增业务操作日志 |
| `DwLayerController` | `GET /list` | 获得数仓分层列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `DwLayerController` | `GET /export-excel` | 导出数仓分层 Excel | 只读查询、预览或导出，不新增业务操作日志 |
| `SubjectDomainController` | `POST /create` | 创建主题域 | `#result` |
| `SubjectDomainController` | `PUT /update` | 更新主题域 | `#updateReqVO.id` |
| `SubjectDomainController` | `PUT /update-status` | 更新主题域状态 | `#reqVO.id` |
| `SubjectDomainController` | `DELETE /delete` | 删除主题域 | `#id` |
| `SubjectDomainController` | `GET /get` | 获得主题域 | 只读查询、预览或导出，不新增业务操作日志 |
| `SubjectDomainController` | `GET /list` | 获得主题域列表（树形数据平铺返回） | 只读查询、预览或导出，不新增业务操作日志 |
