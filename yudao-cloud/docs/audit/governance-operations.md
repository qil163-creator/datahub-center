# 数据治理统一操作日志接入清单

本次仅为显式业务写入口添加原有 `@LogRecord` 注解，沿用现有权限和事务；查询、导出、SQL 预览、样例检测不作为业务变更记录。新增日志复用系统操作日志组件和 `system_operate_log`，仅记录操作摘要及对象编号，不记录请求体、SQL、密码、连接串、自由文本或数据行。

## 接入范围

新增 **233 个入口**，分布在 **51 个控制器**。`bizNo` 使用原有日志组件的模板表达式；`#_ret.data` 为 `CommonResult` 中的业务返回值；无稳定单对象编号的导入及全局同步使用 `0`，摘要说明操作范围。

| 控制器 | 方法 | 操作 | 业务编号 |
|---|---|---|---|
| `category/SafetyCategoryController.java` | `createCategory` | 创建数据分类信息 | `#_ret.data` |
| `category/SafetyCategoryController.java` | `updateCategory` | 更新数据分类信息 | `#updateReqVO.id` |
| `category/SafetyCategoryController.java` | `deleteCategory` | 删除数据分类信息 | `#id` |
| `category/SafetyCategoryController.java` | `deleteCategoryList` | 批量删除数据分类信息 | `#request.items.![id]` |
| `category/SafetyCategoryController.java` | `changeStatus` | 修改分类启停状态 | `#request.id` |
| `category/SafetyCategoryController.java` | `assignLevel` | 批量指定数据分级 | `#request.items.![id]` |
| `category/SafetyCategoryController.java` | `saveMask` | 保存分类展示脱敏配置及规则版本 | `#request.id` |
| `categorycleanrule/CategoryCleanRuleController.java` | `createCategoryCleanRule` | 创建清洗规则类目 | `#_ret.data` |
| `categorycleanrule/CategoryCleanRuleController.java` | `updateCategoryCleanRule` | 更新清洗规则类目 | `#updateReqVO.id` |
| `categorycleanrule/CategoryCleanRuleController.java` | `deleteCategoryCleanRule` | 删除清洗规则类目 | `#id` |
| `categorydata/CategoryDataController.java` | `createCategoryData` | 创建数据分类类目 | `#_ret.data` |
| `categorydata/CategoryDataController.java` | `updateCategoryData` | 更新数据分类类目 | `#updateReqVO.id` |
| `categorydata/CategoryDataController.java` | `deleteCategoryData` | 删除数据分类类目 | `#id` |
| `categorydatameta/CategoryDatametaController.java` | `createCategoryDatameta` | 创建标准数据元类目 | `#_ret.data` |
| `categorydatameta/CategoryDatametaController.java` | `updateCategoryDatameta` | 更新标准数据元类目 | `#updateReqVO.id` |
| `categorydatameta/CategoryDatametaController.java` | `deleteCategoryDatameta` | 删除标准数据元类目 | `#id` |
| `categorydataquality/CategoryDataQualityController.java` | `createCategoryDataQuality` | 创建数据质量类目 | `#_ret.data` |
| `categorydataquality/CategoryDataQualityController.java` | `updateCategoryDataQuality` | 更新数据质量类目 | `#updateReqVO.id` |
| `categorydataquality/CategoryDataQualityController.java` | `deleteCategoryDataQuality` | 删除数据质量类目 | `#id` |
| `categoryterm/CategoryTermController.java` | `createCategoryTerm` | 创建业务术语类目 | `#_ret.data` |
| `categoryterm/CategoryTermController.java` | `updateCategoryTerm` | 更新业务术语类目 | `#reqVO.id` |
| `categoryterm/CategoryTermController.java` | `deleteCategoryTerm` | 删除业务术语类目 | `#id` |
| `doc/StandardDocController.java` | `createDoc` | 创建标准文件登记信息 | `#_ret.data` |
| `doc/StandardDocController.java` | `registerDoc` | 登记标准文件，类目、标准编号和原文必填 | `#_ret.data` |
| `doc/StandardDocController.java` | `updateRegisteredDoc` | 更新标准文件登记工作台元信息 | `#updateReqVO.id` |
| `doc/StandardDocController.java` | `updateDoc` | 更新标准文件登记信息 | `#updateReqVO.id` |
| `doc/StandardDocController.java` | `deleteDoc` | 删除标准文件登记信息 | `#id` |
| `doc/StandardDocController.java` | `deleteDocList` | 批量删除标准文件登记信息 | `#ids` |
| `doc/StandardDocController.java` | `publishDocVersion` | 生成标准文件版本快照 | `#id` |
| `identify/SafetyFieldTagController.java` | `triggerScan` | 触发一次敏感数据扫描（异步） | `#_ret.data` |
| `identify/SafetyFieldTagController.java` | `createTag` | 人工添加打标（直接确认并回写元数据） | `#_ret.data` |
| `identify/SafetyFieldTagController.java` | `confirmTags` | 批量确认打标（回写元数据分级与敏感标记） | `#ids` |
| `identify/SafetyFieldTagController.java` | `rejectTags` | 批量驳回打标 | `#ids` |
| `identify/SafetyFieldTagController.java` | `deleteTag` | 删除打标记录 | `#id` |
| `identify/SafetyIdentifyRuleController.java` | `createRule` | 创建敏感识别规则 | `#_ret.data` |
| `identify/SafetyIdentifyRuleController.java` | `updateRule` | 更新敏感识别规则 | `#updateReqVO.id` |
| `identify/SafetyIdentifyRuleController.java` | `deleteRule` | 删除敏感识别规则 | `#id` |
| `level/SafetyLevelController.java` | `createLevel` | 创建数据分级 | `#_ret.data` |
| `level/SafetyLevelController.java` | `updateLevel` | 更新数据分级 | `#updateReqVO.id` |
| `level/SafetyLevelController.java` | `deleteLevel` | 删除数据分级 | `#id` |
| `level/SafetyLevelController.java` | `deleteLevelList` | 批量删除数据分级 | `#ids` |
| `master/MasterApprovalController.java` | `approve` | 通过人工审批并应用数据变更 | `#req.id` |
| `master/MasterApprovalController.java` | `reject` | 驳回人工审批，正式数据保持原状态 | `#req.id` |
| `master/MasterApprovalController.java` | `reconcile` | 同步权威流程状态或标记超时执行待核查 | `#id` |
| `master/MasterApprovalController.java` | `resolve` | 核查未知结果并记录依据，不重新写入外部数据 | `#req.id` |
| `master/MasterCatalogController.java` | `createCatalog` | 创建主数据目录节点 | `#_ret.data` |
| `master/MasterCatalogController.java` | `updateCatalog` | 更新主数据目录节点 | `#updateReqVO.id` |
| `master/MasterCatalogController.java` | `deleteCatalog` | 删除主数据目录节点 | `#id` |
| `master/MasterCodeRuleController.java` | `create` | 创建编码规则 | `#_ret.data` |
| `master/MasterCodeRuleController.java` | `update` | 修改编码规则 | `#req.id` |
| `master/MasterCodeRuleController.java` | `updateStatus` | 启停编码规则 | `#req.id` |
| `master/MasterCodeRuleController.java` | `delete` | 删除编码规则 | `#id` |
| `master/MasterCodeRuleController.java` | `deleteList` | 批量删除编码规则 | `#ids` |
| `master/MasterDataController.java` | `submit` | 提交主数据变更 | `#_ret.data.id` |
| `master/MasterDataController.java` | `addMasterData` | 提交主数据新增变更 | `#_ret.data.id` |
| `master/MasterDataController.java` | `updateMasterData` | 提交主数据修改变更 | `#_ret.data.id` |
| `master/MasterDataController.java` | `deleteMasterData` | 提交主数据删除变更 | `#_ret.data.id` |
| `master/MasterDistributeController.java` | `updateStatus` | 启用或停用 REST 推送（不发送数据） | `#id` |
| `master/MasterDistributeController.java` | `retry` | 重试最近一次发送前失败的推送 | `#id` |
| `master/MasterDistributeController.java` | `resolve` | 人工核查未知派发结果并停用排期（不发送数据） | `#request.recordId` |
| `master/MasterDistributeController.java` | `createDistribute` | 创建主数据派发任务 | `#_ret.data` |
| `master/MasterDistributeController.java` | `updateDistribute` | 更新主数据派发任务 | `#updateReqVO.id` |
| `master/MasterDistributeController.java` | `deleteDistribute` | 删除主数据派发任务 | `#id` |
| `master/MasterDistributeController.java` | `releaseDistribute` | 发布派发任务（一次性任务立即执行，周期任务开始排期） | `#id` |
| `master/MasterDistributeController.java` | `cancelDistribute` | 取消发布派发任务 | `#id` |
| `master/MasterDistributeController.java` | `handRun` | 手动执行一次派发 | `#id` |
| `master/MasterDistributeController.java` | `stopRun` | 停止指定派发运行（不改变发布及排期，发送后结果仍须核查） | `#request.recordId` |
| `master/MasterDomainController.java` | `create` | 创建主数据域 | `#_ret.data` |
| `master/MasterDomainController.java` | `update` | 修改主数据域 | `#req.id` |
| `master/MasterDomainController.java` | `updateStatus` | 更新主数据域状态 | `#req.id` |
| `master/MasterDomainController.java` | `delete` | 删除主数据域 | `#id` |
| `master/MasterDomainController.java` | `deleteList` | 批量删除主数据域 | `#ids` |
| `master/MasterModelController.java` | `createModel` | 保存主数据模型草稿（不创建物理表） | `#_ret.data` |
| `master/MasterModelController.java` | `updateModel` | 保存主数据模型修改（结构变更保存为草稿） | `#updateReqVO.id` |
| `master/MasterModelController.java` | `cancelReleaseModel` | 下线主数据模型（保留物理表和数据） | `#id` |
| `master/MasterPushCategoryController.java` | `create` | 创建推送类目 | `#_ret.data` |
| `master/MasterPushCategoryController.java` | `update` | 修改推送类目 | `#request.id` |
| `master/MasterPushCategoryController.java` | `status` | 更新推送类目状态 | `#request.id` |
| `master/MasterPushCategoryController.java` | `delete` | 删除推送类目 | `#id` |
| `master/MasterPushCategoryController.java` | `deleteList` | 批量删除推送类目 | `#ids` |
| `metadata/MetaCollectTaskController.java` | `createTask` | 创建元数据采集任务 | `#_ret.data` |
| `metadata/MetaCollectTaskController.java` | `updateTask` | 更新元数据采集任务 | `#updateReqVO.id` |
| `metadata/MetaCollectTaskController.java` | `deleteTask` | 删除元数据采集任务 | `#id` |
| `metadata/MetaCollectTaskController.java` | `updateEnabled` | 任务上下线（发布/取消发布任务） | `#id` |
| `metadata/MetaCollectTaskController.java` | `updateScheduleEnabled` | 调度启停 | `#id` |
| `metadata/MetaCollectTaskController.java` | `runOnce` | 手动执行一次采集 | `#id` |
| `metadata/MetaCollectTaskController.java` | `deleteList` | 批量删除采集任务 | `#ids` |
| `metadata/MetaFileController.java` | `update` | 更新文件业务属性 | `#req.id` |
| `metadata/MetaFileController.java` | `delete` | 删除文件元数据，不删除远端文件 | `#id` |
| `metadata/MetaFileController.java` | `deleteList` | 批量删除文件元数据，不删除远端文件 | `#ids` |
| `metadata/MetaImportController.java` | `importExcel` | 导入元数据字典（处理结果见导入回执） | `#datasourceId` |
| `metadata/MetaImportController.java` | `registerTable` | 手工登记单张表（origin=手工） | `#_ret.data` |
| `metadata/MetaObjectController.java` | `confirmDeleteDb` | 确认删除库（仅待确认删除状态；级联未删除的表与字段，历史与快照保留） | `#id` |
| `metadata/MetaObjectController.java` | `publishDb` | 库级批量发布（发布本库全部正常且校验通过且未发布的表） | `#id` |
| `metadata/MetaTableController.java` | `updateTableBiz` | 维护表的业务/管理属性（逐属性记录手工变更明细） | `#reqVO.id` |
| `metadata/MetaTableController.java` | `confirmDeleteTable` | 确认删除表（仅待确认删除状态；历史与快照保留） | `#id` |
| `metadata/MetaTableController.java` | `publishTable` | 发布表（仅更新发布状态，保留独立定版） | `#id` |
| `metadata/MetaTableController.java` | `unpublishTable` | 取消发布（保留定版镜像与资产引用） | `#id` |
| `metadata/MetaTableController.java` | `updateColumnBiz` | 维护字段的业务/管理属性（逐属性记录手工变更明细） | `#reqVO.id` |
| `metadata/MetaTableController.java` | `batchAssignOwner` | 批量认责（技术/业务负责人，逐表留痕） | `#reqVO.ids` |
| `metadata/MetaTableController.java` | `finalizeTable` | 指定结构版本定版；业务属性按确认时冻结 | `#request.id` |
| `metadata/MetaTableController.java` | `cancelFinalizeTable` | 撤销定版，不改变发布状态与已存在的资产快照引用 | `#request.id` |
| `metadata/MetaTableController.java` | `deleteTable` | 删除未发布且未关联资产目录的表；历史版本保留 | `#request.id` |
| `metadata/MetaTagController.java` | `createTag` | 创建元数据标签 | `#_ret.data` |
| `metadata/MetaTagController.java` | `updateTag` | 更新元数据标签 | `#updateReqVO.id` |
| `metadata/MetaTagController.java` | `deleteTag` | 删除元数据标签 | `#id` |
| `metadata/MetaTagController.java` | `bindTags` | 绑定标签（全量覆盖该对象已绑标签） | `#reqVO.objectId` |
| `metadata/MetaTagController.java` | `unbindTag` | 解绑单个标签 | `#objectId` |
| `permission/SafetyPermissionController.java` | `saveConfig` | 保存表与字段权限配置 | `#saveReqVO.databaseId` |
| `permission/SafetyPermissionController.java` | `bindLegacy` | 核验后将历史策略绑定到精确物理模式和表，不覆盖或自动合并 | `#request.databaseId` |
| `quality/QualityAlertController.java` | `processAlert` | 处理告警 | `#id` |
| `quality/QualityAlertController.java` | `deleteAlert` | 删除告警记录 | `#id` |
| `quality/QualityBriefController.java` | `generateBrief` | 生成简报（按模板渲染变量与附表） | `#_ret.data` |
| `quality/QualityBriefController.java` | `publishBrief` | 发布简报 | `#id` |
| `quality/QualityBriefController.java` | `deleteBrief` | 删除简报 | `#id` |
| `quality/QualityBriefTemplateController.java` | `createTemplate` | 创建简报模板（含段落 + 附列配置） | `#_ret.data` |
| `quality/QualityBriefTemplateController.java` | `updateTemplate` | 更新简报模板（段落全量覆盖） | `#updateReqVO.id` |
| `quality/QualityBriefTemplateController.java` | `deleteTemplate` | 删除简报模板 | `#id` |
| `quality/QualityCheckObjectController.java` | `createCheckObject` | 创建检查对象 | `#_ret.data` |
| `quality/QualityCheckObjectController.java` | `updateCheckObject` | 更新检查对象 | `#updateReqVO.id` |
| `quality/QualityCheckObjectController.java` | `deleteCheckObject` | 删除检查对象 | `#id` |
| `quality/QualityEtlTaskCreationController.java` | `create` | 提交创建质量检测任务（含幂等回执） | `#_ret.data.taskId` |
| `quality/QualityIssueDataController.java` | `rowAction` | 忽略问题行或添加备注 | `#request.auditLogId` |
| `quality/QualityIssueDataController.java` | `updateIssueStatus` | 更新问题数据处理状态 | `#id` |
| `quality/QualityIssueDataController.java` | `deleteIssueData` | 删除问题数据 | `#id` |
| `quality/QualityOrgScoreController.java` | `calculateScore` | 计算评分（依赖周期内该规则集下已执行的检测结果） | `#ruleSetId` |
| `quality/QualityRepairOrderController.java` | `createRepairOrder` | 创建修复工单 | `#_ret.data` |
| `quality/QualityRepairOrderController.java` | `updateRepairOrder` | 更新修复工单 | `#updateReqVO.id` |
| `quality/QualityRepairOrderController.java` | `deleteRepairOrder` | 删除修复工单 | `#id` |
| `quality/QualityRepairOrderController.java` | `approveRepairOrder` | 审批修复工单（通过/拒绝） | `#id` |
| `quality/QualityRepairOrderController.java` | `executeRepairOrder` | 执行修复（真实回源执行修复SQL） | `#id` |
| `quality/QualityRepairOrderController.java` | `resubmitRepairOrder` | 撤销未执行修复单的旧审批并重新送审 | `#id` |
| `quality/QualityRepairOrderController.java` | `verifyRepairOrder` | 验证修复结果（验证通过/验证未通过） | `#id` |
| `quality/QualityRuleController.java` | `createQualityRule` | 创建质量规则 | `#_ret.data` |
| `quality/QualityRuleController.java` | `updateQualityRule` | 更新质量规则 | `#updateReqVO.id` |
| `quality/QualityRuleController.java` | `updateQualityRuleStatus` | 更新质量规则状态 | `#id` |
| `quality/QualityRuleController.java` | `deleteQualityRule` | 删除质量规则 | `#id` |
| `quality/QualityRuleSetController.java` | `createRuleSet` | 创建规则集 | `#_ret.data` |
| `quality/QualityRuleSetController.java` | `updateRuleSet` | 更新规则集 | `#updateReqVO.id` |
| `quality/QualityRuleSetController.java` | `updateRuleSetStatus` | 更新规则集状态 | `#id` |
| `quality/QualityRuleSetController.java` | `deleteRuleSet` | 删除规则集 | `#id` |
| `quality/QualityRuleSetController.java` | `saveRuleSetItems` | 保存规则集明细（全量覆盖） | `#reqVO.ruleSetId` |
| `quality/QualityTaskController.java` | `createTask` | 创建质量任务 | `#_ret.data` |
| `quality/QualityTaskController.java` | `updateTask` | 更新质量任务 | `#updateReqVO.id` |
| `quality/QualityTaskController.java` | `updateTaskStatus` | 更新质量任务状态（上线/下线） | `#id` |
| `quality/QualityTaskController.java` | `deleteTask` | 删除质量任务 | `#id` |
| `quality/QualityTaskController.java` | `executeTask` | 手动执行一次质量任务 | `#id` |
| `quality/QualityTaskTemplateController.java` | `createTemplate` | 创建任务模板 | `#_ret.data` |
| `quality/QualityTaskTemplateController.java` | `updateTemplate` | 更新任务模板 | `#updateReqVO.id` |
| `quality/QualityTaskTemplateController.java` | `updateTemplateStatus` | 更新任务模板状态 | `#id` |
| `quality/QualityTaskTemplateController.java` | `deleteTemplate` | 删除任务模板 | `#id` |
| `quality/QualityTaskTemplateController.java` | `materializeTemplate` | 同步物化：按模板批量生成检测任务 | `#id` |
| `quality/QualityWorkOrderController.java` | `createWorkOrder` | 创建问题工单 | `#_ret.data` |
| `quality/QualityWorkOrderController.java` | `updateWorkOrder` | 更新问题工单 | `#updateReqVO.id` |
| `quality/QualityWorkOrderController.java` | `deleteWorkOrder` | 删除问题工单 | `#id` |
| `quality/QualityWorkOrderController.java` | `reviewNotify` | 人工核验结果未知的通知 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `authorizeNotifyRetry` | 基于人工未送达结论显式授权重试 | `#reqVO.id` |
| `qualityrule/AuditRuleController.java` | `createAuditRule` | 创建稽查规则 | `#_ret.data` |
| `qualityrule/AuditRuleController.java` | `updateAuditRule` | 更新稽查规则 | `#updateReqVO.id` |
| `qualityrule/AuditRuleController.java` | `updateAuditRuleStatus` | 更新稽查规则状态 | `#id` |
| `qualityrule/AuditRuleController.java` | `deleteAuditRule` | 删除稽查规则 | `#id` |
| `qualityrule/CleanRuleController.java` | `createCleanRule` | 创建清洗规则 | `#_ret.data` |
| `qualityrule/CleanRuleController.java` | `updateCleanRule` | 更新清洗规则 | `#updateReqVO.id` |
| `qualityrule/CleanRuleController.java` | `updateCleanRuleStatus` | 更新清洗规则状态 | `#id` |
| `qualityrule/CleanRuleController.java` | `deleteCleanRule` | 删除清洗规则 | `#id` |
| `rule/SafetyRuleController.java` | `createRule` | 创建脱敏规则 | `#_ret.data` |
| `rule/SafetyRuleController.java` | `updateRule` | 更新脱敏规则 | `#updateReqVO.id` |
| `rule/SafetyRuleController.java` | `deleteRule` | 删除脱敏规则 | `#id` |
| `rule/SafetyRuleController.java` | `deleteRuleList` | 批量删除脱敏规则 | `#ids` |
| `rule/SafetyRuleWorkbenchController.java` | `create` | 新建展示脱敏规则并关联分类 | `#_ret.data` |
| `rule/SafetyRuleWorkbenchController.java` | `update` | 修改展示脱敏规则 | `#request.id` |
| `rule/SafetyRuleWorkbenchController.java` | `changeStatus` | 修改展示脱敏规则状态 | `#request.id` |
| `rule/SafetyRuleWorkbenchController.java` | `delete` | 删除展示脱敏规则并解除自身分类关联 | `#request.id` |
| `rule/SafetyRuleWorkbenchController.java` | `deleteList` | 批量删除展示脱敏规则 | `#request.items.![id]` |
| `standardcodeitem/StandardCodeItemController.java` | `createStandardCodeItem` | 创建数据标准--码值 | `#_ret.data` |
| `standardcodeitem/StandardCodeItemController.java` | `updateStandardCodeItem` | 更新数据标准--码值 | `#updateReqVO.id` |
| `standardcodeitem/StandardCodeItemController.java` | `deleteStandardCodeItem` | 删除数据标准--码值 | `#id` |
| `standardcodeitem/StandardCodeItemController.java` | `deleteStandardCodeItemList` | 批量删除数据标准--码值 | `#ids` |
| `standardcodeset/StandardCodeMappingController.java` | `create` | 创建关联配置 | `#_ret.data` |
| `standardcodeset/StandardCodeMappingController.java` | `update` | 更新关联配置 | `#request.id` |
| `standardcodeset/StandardCodeMappingController.java` | `delete` | 删除关联配置 | `#id` |
| `standardcodeset/StandardCodeSetController.java` | `updateStatus` | 切换标准启用状态 | `#id` |
| `standardcodeset/StandardCodeSetController.java` | `createStandardCodeSet` | 创建数据标准--标准码 | `#_ret.data` |
| `standardcodeset/StandardCodeSetController.java` | `updateStandardCodeSet` | 更新数据标准--标准码 | `#updateReqVO.id` |
| `standardcodeset/StandardCodeSetController.java` | `deleteStandardCodeSet` | 删除数据标准--标准码 | `#id` |
| `standardcodeset/StandardCodeSetController.java` | `deleteStandardCodeSetList` | 批量删除数据标准--标准码 | `#ids` |
| `standardcodeset/StandardCodeSetController.java` | `importExcel` | 导入码表与码值（处理结果见导入回执） | `0（批量导入或全局操作）` |
| `standardelement/StandardElementController.java` | `updateStatus` | 切换标准启用状态 | `#id` |
| `standardelement/StandardElementController.java` | `createStandardElement` | 创建数据标准--标准数据元 | `#_ret.data` |
| `standardelement/StandardElementController.java` | `updateStandardElement` | 更新数据标准--标准数据元 | `#updateReqVO.id` |
| `standardelement/StandardElementController.java` | `bindStandardElementCodeSet` | 绑定数据标准--标准数据元的标准码表 | `#id` |
| `standardelement/StandardElementController.java` | `publishStandardElement` | 发布数据标准--标准数据元（草稿 → 已发布） | `#id` |
| `standardelement/StandardElementController.java` | `revokeStandardElementMapping` | 撤销落标映射（同时解除元数据字段与该标准的绑定） | `#mappingId` |
| `standardelement/StandardElementController.java` | `deprecateStandardElement` | 废止数据标准--标准数据元（已发布 → 已废止） | `#id` |
| `standardelement/StandardElementController.java` | `syncModelFieldMappings` | 全局同步建模字段的落标事实（补齐缺失、失效置 STALE） | `0（批量导入或全局操作）` |
| `standardelement/StandardElementController.java` | `importExcel` | 导入标准数据元（处理结果见导入回执） | `0（批量导入或全局操作）` |
| `standardelement/StandardElementController.java` | `deleteStandardElement` | 删除数据标准--标准数据元 | `#id` |
| `standardelement/StandardElementController.java` | `deleteStandardElementList` | 批量删除数据标准--标准数据元 | `#ids` |
| `standardelement/StandardElementRuleController.java` | `create` | 创建关联配置 | `#_ret.data` |
| `standardelement/StandardElementRuleController.java` | `update` | 更新关联配置 | `#request.id` |
| `standardelement/StandardElementRuleController.java` | `delete` | 删除关联配置 | `#id` |
| `standardterm/StandardTermController.java` | `createStandardTerm` | 创建业务术语 | `#_ret.data` |
| `standardterm/StandardTermController.java` | `updateStandardTerm` | 更新业务术语 | `#updateReqVO.id` |
| `standardterm/StandardTermController.java` | `deleteStandardTerm` | 删除业务术语 | `#id` |
| `standardterm/StandardTermController.java` | `deleteStandardTermList` | 批量删除业务术语 | `#ids` |
| `statictask/SafetyStaticTaskController.java` | `createTask` | 创建静态脱敏任务 | `#_ret.data` |
| `statictask/SafetyStaticTaskController.java` | `updateTask` | 更新静态脱敏任务 | `#updateReqVO.id` |
| `statictask/SafetyStaticTaskController.java` | `deleteTask` | 删除静态脱敏任务 | `#id` |
| `statictask/SafetyStaticTaskController.java` | `deleteTaskList` | 批量删除静态脱敏任务 | `#ids` |
| `statictask/SafetyStaticTaskController.java` | `updateTaskStatus` | 更新静态脱敏任务上下线状态 | `#id` |
| `statictask/SafetyStaticTaskController.java` | `executeTask` | 手动执行静态脱敏任务（异步） | `#id` |
| `whitelist/SafetyWhitelistController.java` | `createWhitelist` | 创建脱敏白名单 | `#_ret.data` |
| `whitelist/SafetyWhitelistController.java` | `updateWhitelist` | 更新脱敏白名单 | `#updateReqVO.id` |
| `whitelist/SafetyWhitelistController.java` | `deleteWhitelist` | 删除脱敏白名单 | `#id` |
| `whitelist/SafetyWhitelistController.java` | `deleteWhitelistList` | 批量删除脱敏白名单 | `#ids` |
| `whitelist/SafetyWhitelistController.java` | `submitWhitelist` | 提交审批（草稿/已驳回 → 审批中） | `#id` |
| `whitelist/SafetyWhitelistController.java` | `approveWhitelist` | 审批通过（审批中 → 已通过，并自动启用） | `#id` |
| `whitelist/SafetyWhitelistController.java` | `rejectWhitelist` | 审批驳回（审批中 → 已驳回，并停用） | `#id` |
| `whitelist/SafetyWhitelistWorkbenchController.java` | `create` | 配置分类范围白名单并记录本地授权依据 | `#_ret.data` |
| `whitelist/SafetyWhitelistWorkbenchController.java` | `update` | 修改分类范围白名单 | `#req.id` |
| `whitelist/SafetyWhitelistWorkbenchController.java` | `status` | 修改分类范围白名单状态 | `#req.id` |
| `whitelist/SafetyWhitelistWorkbenchController.java` | `delete` | 删除停用分类白名单 | `#req.id` |
| `whitelist/SafetyWhitelistWorkbenchController.java` | `deleteList` | 原子批量删除停用分类白名单 | `#req.items.![id]` |

| `quality/QualityWorkOrderController.java` | `assign` | 分派质量工单 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `claim` | 领取质量工单 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `transfer` | 转交质量工单 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `submit-resolution` | 提交质量工单处理结果 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `recheck` | 提交质量工单复检 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `confirm` | 确认质量工单处理结论 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `withdraw-submission` | 撤销质量工单提交 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `extend-deadline` | 延长质量工单截止时间 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `reopen` | 重新打开质量工单 | `#reqVO.id` |
| `quality/QualityWorkOrderController.java` | `request-coordination` | 请求质量工单协调 | `#reqVO.id` |

## 明确排除及已有审计

| 方法 | 原因 |
|---|---|
| `MasterCodeRule.preview` | 仅预览编码，不发放流水号 |
| `MasterData.getMasterDataPage` | 仅分页查询 |
| `SafetyPermission.preflight` | 仅写授权和 SQL 预检，不执行写入 |
| `QualityIssueData.rows` | 仅查询问题行 |
| `QualityRepairOrder.previewRepairSql` | 仅预览修复 SQL，不落库 |
| `QualityRule.previewSql` | 仅预览规则 SQL |
| `QualityTask.sample` | 仅未保存评测的样例监测 |
| `QualityTask.spotCheck` | 仅权限范围内抽查评测，不保存配置 |
| `QualityTask.suggest` | 仅生成评测候选，不保存任务 |
| `AuditRule.previewSql` | 仅预览稽查 SQL |
| `CleanRule.testCleanRule` | 仅在线测试清洗规则 |
| `StandardElementRule.sample` | 仅检测字符样例 |
| `MasterModel.deleteModel` | 已有领域 auditModel，避免重复 |
| `MasterModel.deleteModelList` | 逐对象已有领域 auditModel，避免重复 |
| `MasterModel.releaseModel` | 已有领域 auditModel，保留结果核查语义 |
| `MasterModel.verifyRelease` | 已有领域 auditModel，避免重复 |

全部 GET 查询、下载、导出入口均不增加业务变更日志。工单生命周期中未声明 `@Operation` 的十个写入口显式填写 `subType` 和 `success`。主数据模型修改入口同时覆盖普通属性修改和结构草稿保存；业务直接使用既有 `@LogRecord` / `ILogRecordService`，由既有 `LogRecordServiceImpl` 处理批量编号、事务提交后投递和同请求细粒度日志去重，避免结构草稿路径重复。

## 操作名与操作内容

233 个入口保留原 `subType`、`bizNo` 和成功条件，仅将 `success` 改为包含具体对象的业务叙述。操作名用于分类，内容记录本次涉及的名称、编号、编码、请求状态、版本或回执状态，不再复用接口摘要。

- 业务术语新增记录名称和英文名称；修改读取真实旧对象名称、状态，与本次提交的新名称、状态分别呈现；单条删除在删除前保留真实名称。
- 36 个控制器通过已有本领域 Service 的只读入口补充单对象名称和状态，使用原有 `LogRecordContext.putVariable`，只保存字符串，不把 DO、VO、请求体或配置全文放入日志上下文。补充读取失败时回退为对象编号，原写入方法继续负责业务校验及异常结果。
- 批量术语删除使用已有批量查询单次读取仍存在的术语名称，不逐个调用可能抛出不存在异常的详情接口；其他批量操作明确列出请求对象编号，不改变原有原子性和不存在处理。
- 复杂映射、元数据版本、权限和工单流转使用对象编号、修订、明确目标状态或回执编号。审批意见、自由文本、SQL、数据行、脱敏替换内容、凭据和完整配置不进入统一操作内容。
- 可选名称和上下文均使用非空回退，避免 SDK 在空值情况下保留未解析模板。异步采集、脱敏、派发和质量检测记录“发起”或“提交”，最终结果仍由对应运行回执证明。

示例：操作名“删除业务术语”，内容“删除了业务术语【客户】”；操作名“更新业务术语”，内容“修改了业务术语，名称从【客户】改为【客户主体】，状态【0】→【1】”。

单对象名称属于本次操作前的只读观测，不替代 Service 内持锁事务的领域审计快照；原有领域审计继续保留。

## 结果语义

全部新增注解标记 `extra = "BUSINESS_OPERATION"`，使用 `successCondition = "{{#_ret.code == 0 && #_ret.data != false}}"` 仅记录请求成功；不新建独立操作日志注解或切面。

- 异步扫描、采集、质量检测、脱敏、派发等记录的是发起执行动作，执行结果仍以各自运行记录为准。
- 主数据增删改入口返回变更单或审批状态，统一操作名明确为“提交变更”，不把等待审批写成数据已生效。
- Excel 导入可能存在部分成功，统一操作名指向导入回执；不把统一响应成功视为所有行均成功。
- 原有领域审计（元数据变更明细、主数据变更单、质量结果、安全写授权等）继续保留，统一日志作为管理端动作入口记录。

## 可用于运行验证的隔离数据

`/governance/standard-term` 的 create/update/delete 可创建带唯一前缀的临时术语，字段 `name`、`definition`、`status` 必填；新增回包为 Long 对象编号。建议使用唯一标记后按 `bizId` 和操作模块检索统一日志，最后删除临时术语。发布可使用独立临时标准数据元，但需遵守该业务完整性校验。

本清单是源码覆盖记录；实际运行落库和页面查询由主任务另行验证。

编号表达式使用原生 `{{(表达式) ?: 0}}`，可空编号显式回退为模块范围 0；集合仍逐项处理，空成功集合不记录。
