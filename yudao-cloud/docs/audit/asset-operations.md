# 数据资产统一操作日志接入清单

本清单记录管理端关键业务操作的系统操作日志接入。直接复用既有 `@LogRecord` 注解、`ILogRecordService` 与系统日志写入链路；不改变接口权限、对象范围和领域独立审计。

新增、修改、删除、状态变更、发布、授权、审批和执行等入口通过原有 `@LogRecord` 组件接入；`successCondition` 检查返回 `CommonResult` 的成功状态及非 `false` 业务结果，事务提交后记录。`bizNo` 使用 `{{表达式}}` 直接读取请求编号或 `#_ret.data` 中的生成编号，`extra="BUSINESS_OPERATION"` 标识统一业务入口。日志只提取声明的业务编号，不保存请求体、SQL、密钥、签名、审批正文或响应载荷。日志写入失败不回滚已完成业务，保留可定位的服务告警。

只读查询、只读执行、预览、校验和下载导出不新增领域操作日志。既有领域路径已写入系统日志时保留原记录，避免重复。

本次新增 `@LogRecord` 注解：89 个。

| 控制器 | 接口 | 动作 | 业务编号表达式 / 处理说明 |
|---|---|---|---|
| `AssetTagController` | `POST /create` | 创建资产标签 | `#_ret.data` |
| `AssetTagController` | `PUT /update` | 更新资产标签 | `#reqVO.id` |
| `AssetTagController` | `PUT /update-status` | 启用/停用资产标签 | `#id` |
| `AssetTagController` | `DELETE /delete` | 删除资产标签 | `#id` |
| `AssetTagController` | `POST /delete-list` | 按修订号原子批量删除禁用且无引用的标签 | `#req.items.![id]` |
| `AssetTagController` | `GET /linked-assets` | 分页读取维护范围内的关联资产 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTagController` | `DELETE /unbind` | 维护范围内按修订号移除单条资产标签关联 | `#relationId` |
| `AssetTagController` | `GET /get` | 获得资产标签详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTagController` | `GET /page` | 获得资产标签分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTagController` | `GET /list` | 获得资产标签列表（下拉选择用） | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTagController` | `GET /asset-list` | 获得资产已绑定标签 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTagController` | `PUT /bind` | 全量覆盖资产标签 | `#reqVO.assetId` |
| `CategoryDataAssetController` | `POST /create` | 创建数据资产类目管理 | `#_ret.data` |
| `CategoryDataAssetController` | `PUT /update` | 更新数据资产类目管理 | `#updateReqVO.id` |
| `CategoryDataAssetController` | `DELETE /delete` | 删除数据资产类目管理 | `#id` |
| `CategoryDataAssetController` | `GET /get` | 获得数据资产类目管理 | 只读查询、预览或导出，不新增业务操作日志 |
| `CategoryDataAssetController` | `GET /list` | 获得数据资产类目管理列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `CategoryDataAssetController` | `GET /export-excel` | 导出数据资产类目管理 Excel | 只读查询、预览或导出，不新增业务操作日志 |
| `CategoryTagController` | `POST /create` | 创建标签类目 | `#_ret.data` |
| `CategoryTagController` | `PUT /update` | 更新标签类目 | `#reqVO.id` |
| `CategoryTagController` | `DELETE /delete` | 删除标签类目 | `#id` |
| `CategoryTagController` | `GET /get` | 获得标签类目 | 只读查询、预览或导出，不新增业务操作日志 |
| `CategoryTagController` | `GET /list` | 获得标签类目列表（前端组装树） | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetEtlBatchBindingController` | `GET /get` | 读取当前资产关联的具体批次及实时准入摘要 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetEtlBatchBindingController` | `GET /history` | 按修订倒序读取资产批次关联历史，不代表当前准入 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetEtlBatchBindingController` | `POST /bind` | 绑定明确批次并追加关联修订历史 | `#request.assetId` |
| `AssetEtlBatchBindingController` | `GET /rows` | 读取所绑定批次的准入数据，实时复核源对象权限 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetImportRowController` | `POST /update` | 以修订号维护本地迁入行并记录前后变更 | `#req.id` |
| `AssetImportRowController` | `GET /log-page` | 在资产维护范围内查看迁入行修改记录 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetRegistrationContextController` | `GET /registration-context` | 读取已启用的真实建模选项，板块与市场分类独立 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetRegistrationContextController` | `POST /registration-name-preview` | 按真实候选与持久建模规则生成推荐名称，不修改物理表 | 只读名称预览，无持久写入 |
| `AssetRegistrationController` | `GET /registration-candidates` | 读取真实元数据/已发布 API 注册候选，平台注册人员可见，不代表技术交付授权 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetRegistrationController` | `POST /register-generic` | 从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用 | `#_ret.data` |
| `DataAssetController` | `GET /navigation-categories` | 资产地图的平台管理分类导航，不开放类目维护权限 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /page` | 获得数据资产分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /facets` | 获得资产分组统计（业务分类 / 主题域 / 数仓分层） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /registration-options` | 获得资产注册与维护可选的分类及主题域，沿用当前登录人的可见范围 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /candidate-page` | 获得待编目的已发布元数据分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `POST /batch-register` | 批量编目数据资产 | `#_ret.data` |
| `DataAssetController` | `PUT /update` | 维护数据资产 | `#reqVO.id` |
| `DataAssetController` | `GET /column-get` | 读取资产范围内当前字段的治理属性及修订号 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `PUT /update-column` | 维护资产字段的业务与标准属性 | `#reqVO.assetId` |
| `DataAssetController` | `PUT /refresh` | 刷新资产引用的元数据定版与运行指标 | `#id` |
| `DataAssetController` | `PUT /publish` | 发布数据资产 | `#id` |
| `DataAssetController` | `PUT /unpublish` | 停止新申请并保留既有授权 | `#id` |
| `DataAssetController` | `PUT /stop-supply` | 停止供数并持久触发全部存量授权撤销 | `#req.assetId` |
| `DataAssetController` | `PUT /catalog-delivery/handle` | 重查未知回执或明确拒绝后受控重投 | `#req.assetId` |
| `DataAssetController` | `DELETE /delete` | 删除未发布的数据资产 | `#id` |
| `DataAssetController` | `GET /get` | 获得数据资产详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /preview` | 只读预览资产数据 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `POST /preview` | 带过滤条件的只读预览资产数据 | 只读资产数据预览 |
| `DataAssetController` | `POST /draft` | 创建未挂接技术资源的政务目录草稿 | `#_ret.data` |
| `DataAssetController` | `POST /tech-link` | 挂接技术资源 | `#reqVO.assetId` |
| `DataAssetController` | `GET /tech-link/list` | 查询技术资源关联 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `POST /catalog-version/submit` | 提交目录版本审核 | `#assetId` |
| `DataAssetController` | `PUT /catalog-version/check` | 审核目录版本 | `#reqVO.id` |
| `DataAssetController` | `PUT /catalog-version/publish` | 发布已审核通过的目录版本 | `#id` |
| `DataAssetController` | `GET /catalog-version/list` | 查询目录业务版本 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `GET /publication-readiness` | 只读评估发布证据缺口及历史申请影响 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataAssetController` | `POST /ticket` | 提交校核工单 | `#_ret.data` |
| `DataAssetController` | `PUT /ticket/claim` | 认领校核工单 | `#id` |
| `DataAssetController` | `PUT /ticket/feedback` | 反馈校核处理结果 | `#id` |
| `DataAssetController` | `PUT /ticket/review` | 复核校核工单 | `#id` |
| `DataAssetController` | `GET /ticket/page` | 校核工单分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataQueryController` | `GET /datasource-list` | 获得可查询的数据源列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataQueryController` | `GET /table-list` | 获得数据源下的表名列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataQueryController` | `GET /column-list` | 获得数据源下指定表的字段列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataQueryController` | `POST /execute` | 只读执行单条 SELECT 查询 | 只读 SELECT 查询，访问轨迹沿用 API 访问日志 |
| `DataQueryFileController` | `POST /create` | 创建目录或 SQL 脚本 | `#_ret.data` |
| `DataQueryFileController` | `PUT /update` | 更新目录或 SQL 脚本 | `#reqVO.id` |
| `DataQueryFileController` | `DELETE /delete` | 删除目录或 SQL 脚本 | `#id` |
| `DataQueryFileController` | `GET /get` | 获得目录或 SQL 脚本详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataQueryFileController` | `GET /list` | 获得文件目录扁平列表（前端组装树） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketAccessController` | `GET /consumption-history` | 当前用户最近100次额度消费回执（不包含数据内容） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketAccessController` | `GET /consumption-balances` | 当前用户已发生消费的批准来源余额 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketAccessController` | `GET /consumption-receipt` | 按稳定请求键查询本人的历史消费回执 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketAccessController` | `GET /check` | 当前用户是否拥有该资产的有效集市授权 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketAccessController` | `GET /preview` | 按有效集市授权预览库表（收回/过期立即不可用） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `POST /create` | 提交资源申请 | `#_ret.data` |
| `MarketApplyController` | `PUT /update` | 修改草稿申请 | `#reqVO.id` |
| `MarketApplyController` | `PUT /submit` | 提交草稿进入待审，内容冻结 | `#id` |
| `MarketApplyController` | `DELETE /delete` | 删除草稿/驳回/已撤销申请 | `#id` |
| `MarketApplyController` | `PUT /cancel` | 撤销自己的申请 | `#id` |
| `MarketApplyController` | `PUT /check` | 审批申请 | `#reqVO.id` |
| `MarketApplyController` | `PUT /auth` | 开通或取消授权 | `#id` |
| `MarketApplyController` | `PUT /revoke` | 收回已通过的授权 | `#id` |
| `MarketApplyController` | `GET /get` | 申请详情（含审批轨迹） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `GET /page` | 申请分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `GET /available-apps` | 当前人员可代表的应用 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `GET /available-apis` | 当前资源已绑定、可申请的 API | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `PUT /supplement` | 要求一次性补正材料，不转为驳回 | `#reqVO.id` |
| `MarketApplyController` | `PUT /resubmit` | 补正后重新提交，生成新修订快照 | `#id` |
| `MarketApplyController` | `PUT /renew` | 续期已通过申请，生成新修订并重新交付 | `#reqVO.id` |
| `MarketApplyController` | `POST /renewal-request` | 提交续期申请（新申请，需重新审批） | `#_ret.data` |
| `MarketApplyController` | `PUT /pause` | 暂停有效授权 | `#reqVO.id` |
| `MarketApplyController` | `PUT /resume` | 恢复已暂停授权 | `#id` |
| `MarketApplyController` | `PUT /retry-delivery` | 重试待执行或失败的交付操作 | 全局操作，编号 0 |
| `MarketApplyController` | `GET /delivery-operation` | 查看原交付操作状态（仅授权提供方） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `GET /delivery-evidence` | 只读核对原始交付证据；不批准、不迁移、不重发、不证明当前可访问 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `PUT /recheck-delivery` | 人工按原操作身份核查未知结果；不重发交付命令 | `#_ret.data.applyId` |
| `MarketApplyController` | `POST /redeliver-delivery` | 交付受控重投：仅限冻结命令完整匹配的撤销/暂停 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次；GRANT 拒绝 | `#_ret.data.applyId` |
| `MarketApplyController` | `POST /delivery-review` | 记录待核查结论与证据审计；不恢复、撤销或重新投递授权 | `#_ret.data.applyId` |
| `MarketApplyController` | `POST /delivery-disposal` | 历史交付实际处置：收回授权，或在原回执与正式批准均匹配时确认原批准 | `#_ret.data.applyId` |
| `MarketApplyController` | `POST /delivery-disposal-batch` | 历史交付批量处置：逐条独立执行并逐条返回结果（最多二十条，部分成功不整体回滚） | `#_ret.data.items.?[success].![result.applyId]` |
| `MarketApplyController` | `GET /delivery-reviews` | 查看原交付核查结论历史（每页最多五十项） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketApplyController` | `GET /eligible-apis` | 当前资产可证明关联的已绑定 API | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCategoryController` | `GET /simple-list` | 市场导航和发布表单使用的启用类目 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCategoryController` | `GET /list` | 查询平台集市类目树节点 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCategoryController` | `GET /get` | 读取集市类目 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCategoryController` | `POST /create` | 新增集市类目 | `#_ret.data` |
| `MarketCategoryController` | `PUT /update` | 修改集市类目 | `#req.id` |
| `MarketCategoryController` | `PUT /update-status` | 启用或停用集市类目 | `#req.id` |
| `MarketCategoryController` | `DELETE /delete` | 删除无引用的集市类目 | `#id` |
| `MarketCategoryController` | `DELETE /delete-list` | 原子删除无引用的集市类目集合 | `#ids` |
| `MarketCheckController` | `GET /page` | 服务审批分页；来源工单按审批权限平台可见，本地申请按提供方对象范围 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCheckController` | `GET /get` | 服务审批只读详情；来源快照不代表当前访问授权 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCheckController` | `GET /applicants` | 可见工单申请人筛选，最多返回五百项；来源人员不作为本地身份 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketCheckController` | `PUT /check` | 保存审批意见；来源工单不授予真实数据权限，本地申请复用原审批链 | `#req.origin == 'NATIVE' ? #req.id : 0` |
| `MarketCommentController` | `POST /create` | 发表评价 | `#_ret.data` |
| `MarketCommentController` | `PUT /update` | 修改自己的评价 | `#reqVO.id` |
| `MarketCommentController` | `DELETE /delete` | 删除自己的评价 | `#id` |
| `MarketCommentController` | `PUT /reply` | 资产方回复评价 | `#reqVO.id` |
| `MarketCommentController` | `GET /page` | 评价分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDemandController` | `POST /create` | 创建数据需求 | `#_ret.data` |
| `MarketDemandController` | `PUT /update` | 修改数据需求 | `#reqVO.id` |
| `MarketDemandController` | `DELETE /delete` | 删除草稿需求 | `#id` |
| `MarketDemandController` | `PUT /publish` | 发布到需求大厅 | `#id` |
| `MarketDemandController` | `PUT /close` | 关闭、撤销或驳回需求 | `#reqVO.id` |
| `MarketDemandController` | `GET /get` | 需求详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDemandController` | `GET /page` | 需求分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDemandController` | `POST /reply` | 响应需求 | `#reqVO.demandId` |
| `MarketDemandController` | `POST /match` | 把已有资产匹配到需求 | `#reqVO.demandId` |
| `MarketDemandController` | `PUT /match/confirm` | 需求方确认匹配 | `#reqVO.id` |
| `MarketDemandController` | `PUT /accept` | 验收已交付需求，已匹配不等于已解决 | `#id` |
| `MarketDemandController` | `GET /suggest` | 按需求推荐已发布资产 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDepartmentAssetController` | `GET /page` | 查看当前用户相同部门成员的全部申请资产，不自动包含子部门 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDepartmentAssetController` | `GET /get` | 按指定申请编号读取同部门资产详情，查看不产生消费授权 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDepartmentAssetController` | `GET /preview` | 读取指定同部门申请的复制数据；原生实时预览仍校验本人真实批准 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDepartmentAssetController` | `GET /api-document` | 读取指定申请的 API 文档；不执行来源 API，不转授同部门其他人的合同 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketDepartmentAssetController` | `GET /categories` | 获取当前同部门申请资产实际关联的集市类目 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMartController` | `GET /topics` | 启用的顶级市场专题及其已发布资产数 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMartController` | `GET /listing` | 读取市场展示信息供发布表单回显，不修改发布状态 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMartController` | `PUT /publish` | 保存市场展示配置并沿资产目录原状态机发布 | `#reqVO.assetId` |
| `MarketMartController` | `GET /page` | 检索已发布资产，收藏与授权均绑定当前登录用户 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMartController` | `GET /statistics` | 已发布总数及当前用户授权、收藏、在途申请资产数 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMartController` | `PUT /favorite` | 设置当前用户收藏状态，重复设置保持幂等 | `#reqVO.assetId` |
| `MarketMyAssetController` | `GET /applications/page` | 分页查看本人的申请记录 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMyAssetController` | `GET /applications/get` | 查看本人的申请详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMyAssetController` | `GET /page` | 本人的已批准资产，保留过期、暂停和撤销历史 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMyAssetController` | `GET /get` | 本人的批准详情；读取历史不恢复消费授权 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMyAssetController` | `GET /preview` | 按本人指定批准实时核验并预览库表 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketMyAssetController` | `GET /api-document` | 实时核对本人批准及固定 API 合同后查看文档 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketResourceController` | `GET /page` | 已发布资产分页（集市） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketResourceController` | `GET /get` | 已发布资产详情（集市消费视图，不重复计浏览） | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketResourceController` | `GET /facets` | 集市资产分组统计 | 只读查询、预览或导出，不新增业务操作日志 |
| `MarketResourceController` | `POST /bind-api` | 绑定资源可交付 API | `#reqVO.assetId` |
| `MarketResourceController` | `GET /endpoints` | 查询资源已绑定交付端点 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetQualityTaskController` | `POST /create` | 创建资产质量任务 | `#_ret.data` |
| `AssetQualityTaskController` | `PUT /update` | 更新资产质量任务 | `#reqVO.id` |
| `AssetQualityTaskController` | `DELETE /delete` | 删除资产质量任务 | `#id` |
| `AssetQualityTaskController` | `GET /list` | 获得资产质量任务列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetQualityTaskController` | `POST /execute` | 执行资产质量任务 | `#id` |
| `AssetSensitiveListController` | `GET /page` | 在资产维护范围内分页查询敏感清单 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetSensitiveListController` | `GET /get` | 查询敏感清单详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetSensitiveListController` | `POST /create` | 创建敏感清单记录，默认禁用 | `#_ret.data` |
| `AssetSensitiveListController` | `PUT /update` | 修改禁用状态的敏感清单记录 | `#req.id` |
| `AssetSensitiveListController` | `PUT /change-status` | 按修订号启用或禁用敏感清单记录 | `#req.id` |
| `AssetSensitiveListController` | `DELETE /delete` | 删除禁用状态的敏感清单记录 | `#id` |
| `AssetSensitiveListController` | `POST /delete-list` | 原子校验所选范围并批量删除禁用项，返回跳过的启用项 | `#_ret.data.deletedIds` |
| `AssetSensitiveListController` | `GET /categories` | 查询治理侧分类目录及叶分类 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetSensitiveListController` | `GET /asset-options` | 查询维护范围内可绑定的真实资产 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetSensitiveListController` | `GET /column-options` | 查询可维护资产的当前字段，支持迁入字段 | 只读查询、预览或导出，不新增业务操作日志 |
| `StandardAssociationController` | `GET /page` | 获得当前用户可维护的标准关联资产分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTermController` | `GET /simple-list` | 获得启用中的治理业务术语（最多50条，可按关键字搜索） | 只读查询、预览或导出，不新增业务操作日志 |
| `AssetTermController` | `PUT /bind-column` | 资产字段绑定/解除治理业务术语 | `#reqVO.assetId` |

资产敏感清单批量删除仅记录返回的 `deletedIds`，跳过项不记成删除成功。历史交付批量处置仅记录返回结果中成功项的申请编号；空成功集合不产生日志。市场来源工单的外部字符串编号无法存入系统日志的 Long 编号列，统一记录为编号 0；本地申请保留真实申请编号。集市重试等全局调度入口记录发起动作，不代表外部交付已经完成。

资产异步退役删除记录“提交资产退役删除请求”；返回 `false` 表示请求已受理、解绑尚未完成，此入口仅检查 `CommonResult.code`，不把它表述成删除完成。

编号表达式使用原生 `{{(表达式) ?: 0}}`，可空编号显式回退为模块范围 0；集合仍逐项处理，空成功集合不记录。
