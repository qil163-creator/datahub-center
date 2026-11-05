# 数据服务统一操作日志接入清单

本清单记录管理端关键业务操作的系统操作日志接入。直接复用既有 `@LogRecord` 注解、`ILogRecordService` 与系统日志写入链路；不改变接口权限、对象范围和领域独立审计。

新增、修改、删除、状态变更、发布、授权、审批和执行等入口通过原有 `@LogRecord` 组件接入；`successCondition` 检查返回 `CommonResult` 的成功状态及非 `false` 业务结果，事务提交后记录。`bizNo` 使用 `{{表达式}}` 直接读取请求编号或 `#_ret.data` 中的生成编号，`extra="BUSINESS_OPERATION"` 标识统一业务入口。日志只提取声明的业务编号，不保存请求体、SQL、密钥、签名、审批正文或响应载荷。日志写入失败不回滚已完成业务，保留可定位的服务告警。

只读查询、只读执行、预览、校验和下载导出不新增领域操作日志。既有领域路径已写入系统日志时保留原记录，避免重复。

本次新增 `@LogRecord` 注解：23 个。

| 控制器 | 接口 | 动作 | 业务编号表达式 / 处理说明 |
|---|---|---|---|
| `ApiCategoryController` | `POST /create` | 创建服务分类 | `#_ret.data` |
| `ApiCategoryController` | `PUT /update` | 更新服务分类 | `#updateReqVO.id` |
| `ApiCategoryController` | `PUT /update-status` | 更新服务分类状态 | `#reqVO.id` |
| `ApiCategoryController` | `DELETE /delete` | 删除服务分类 | `#id` |
| `ApiCategoryController` | `GET /get` | 获得服务分类 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiCategoryController` | `GET /list` | 获得服务分类列表（树形数据平铺返回） | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiLogController` | `GET /page` | 获得调用记录分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiLogController` | `GET /get` | 获得调用记录详情 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiLogController` | `DELETE /delete` | 删除调用记录 | `#id` |
| `ApiLogController` | `GET /overview` | 数据概览：今日/本月调用量、API 数、近 30 天趋势、调用排行 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiLogController` | `GET /statistics` | 监控统计：调用量/成功率/耗时/趋势/排行 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiTestController` | `GET /catalog` | 获得平台内已上线 API 测试目录 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiTestController` | `GET /detail` | 获得已发布 API 的最小测试定义 | 只读查询、预览或导出，不新增业务操作日志 |
| `ApiTestController` | `POST /send` | 发送登记 API 或公开 HTTP 地址的真实测试请求 | `#reqVO.apiId` |
| `SupplyAppController` | `GET /api-auth/etl-audit-page` | 按编号倒序查看独立 ETL 授权的不可变审计历史 | 只读查询、预览或导出，不新增业务操作日志 |
| `SupplyAppController` | `POST /create` | 创建应用（自动生成秘钥） | `#_ret.data.id` |
| `SupplyAppController` | `PUT /update` | 更新应用 | `#updateReqVO.id` |
| `SupplyAppController` | `PUT /reset-secret` | 重置应用秘钥 | `#id` |
| `SupplyAppController` | `DELETE /delete` | 删除应用（级联删除 API 授权） | `#id` |
| `SupplyAppController` | `GET /get` | 获得应用 | 只读查询、预览或导出，不新增业务操作日志 |
| `SupplyAppController` | `GET /page` | 获得应用分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `SupplyAppController` | `PUT /update-status` | 启用、暂停或撤销应用 | `#id` |
| `SupplyAppController` | `GET /api-auth/list` | 获得应用的 API 授权列表 | 只读查询、预览或导出，不新增业务操作日志 |
| `SupplyAppController` | `GET /api-auth/get` | 按编号获得应用 API 授权及实际状态 | 只读查询、预览或导出，不新增业务操作日志 |
| `SupplyAppController` | `POST /api-auth/create` | 新增应用 API 授权 | `#_ret.data` |
| `SupplyAppController` | `PUT /api-auth/update` | 修改普通独立管理员 API 授权的有效期和描述 | 既有 auditAdminUpdate 已在事务提交后汇聚系统操作日志 |
| `SupplyAppController` | `PUT /api-auth/enable` | 明确重新启用应用 API 授权聚合 | `#id` |
| `SupplyAppController` | `PUT /api-auth/control` | 整体启用或禁用应用 API 授权（按聚合版本防止旧页面覆盖） | `#id` |
| `SupplyAppController` | `PUT /api-auth/revoke` | 永久关闭管理端 ADMIN 授权（终态；市场来源须由来源系统撤销） | `#id` |
| `SupplyAppController` | `DELETE /api-auth/delete` | 整体禁用应用 API 授权（保留来源记录） | `#id` |
| `DataApiController` | `POST /create` | 创建 API 服务 | `#_ret.data` |
| `DataApiController` | `PUT /update` | 更新 API 服务 | `#updateReqVO.id` |
| `DataApiController` | `PUT /update-status` | 上线/下线 API 服务 | `#reqVO.id` |
| `DataApiController` | `DELETE /delete` | 删除 API 服务 | `#id` |
| `DataApiController` | `GET /get` | 获得 API 服务 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /page` | 获得 API 服务分页 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /simple-list` | 获得 API 服务精简列表（接口测试/应用授权下拉用） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /database-list` | 获得数据源精简列表（向导下拉用） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /public-base-url` | 获得对外网关基址（未配置时为空，不得猜测） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /table-list` | 获得数据源下的表名列表（向导下拉用） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `GET /table-columns` | 获得数据表列元数据（向导选择请求参数/返回字段用） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiController` | `POST /test` | 草稿只读预览（配置可未保存，拒绝写入和转发） | 只读草稿预览 |
| `DataApiController` | `POST /sign` | 在线签名（v1 旧协议或 v2 HMAC-SM3） | 签名计算，无业务持久写入且涉及凭据 |
| `DataApiController` | `POST /publish` | 发布当前草稿为不可变快照 | `#reqVO.id` |
| `DataApiController` | `POST /rollback` | 回滚到既有发布快照 | `#reqVO.id` |
| `DataApiController` | `GET /release/list` | 获得 API 发布历史 | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiOperationController` | `GET /list` | 游标查询当前平台写操作（至多51条，第51条表示仍有下一页） | 只读查询、预览或导出，不新增业务操作日志 |
| `DataApiOperationController` | `POST /resolve` | 记录未知写操作核查证据；不会重试或释放业务键 | `#request.id` |

真实接口测试记录“发起请求”，不代表被测服务或外部副作用成功；自由 URL 模式没有平台业务编号时使用 0。创建应用只提取响应的 `id`，不记录一次性密钥；重置密钥只记录应用编号。API 发布记录 API 编号，发布快照继续由既有发布历史管理。

编号表达式使用原生 `{{(表达式) ?: 0}}`，可空编号显式回退为模块范围 0；集合仍逐项处理，空成功集合不记录。
