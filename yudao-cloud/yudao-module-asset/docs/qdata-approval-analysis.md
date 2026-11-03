# qData 审批工单复刻记录

## 范围与证据

2026-10-22 对用户授权的 qData 演示环境进行只读分析，目标 `/da/assetMartAudit`。保留本项目动态导航与服务审批菜单，只替换业务区。使用 REA 被动 DOM/脚本证据，Playwright 进行页面导航、打开弹窗与读取响应；没有向源站提交同意、驳回或新增申请。

- 原始列表脚本：本地 `target/qdata-approval-list-source.js`；历史脚本证据 `ev_faa403157e2b466f09a4992ebb966100a66f4db763c025f59b7f6bebd78d7090`。历史记录在早期 REA 会话中产生，当前会话导出不包含该完整记录。
- 同意弹窗 REA 证据：`ev_a10b1e29ec1a86511365e10678acc6adbbcf5873121f39709111be73554ad800`。
- 驳回弹窗 REA 证据：`ev_a2a0fbf0b8b6eacb5ca87b57ae4b4e52bfa069fab890ba85f779d673f539b6cd`。
- 当前会话证据导出、源站弹窗截图、脱敏白名单数据和本地浏览器结果保存在 `target/`，均为工作产物，不作为账号或认证状态载体。
- 图标从用户参考图裁切；目录 `views/market/check/reference-assets` 的 manifest 与布局蓝图记录来源和尺寸。未替换项目导航和全局主题。

## 源站行为

列表按创建时间倒序，筛选资产名称、状态、申请人，提供分页、编号/申请时间排序、刷新、列设置。同意与驳回仅在未授权/待授权时可用。下架记录详情不可用。实际列表没有“新增”入口；申请由集市资产的申请流程产生，因此没有在本页额外设计新增按钮或弹窗。

详情地址 `/da/assetMartAudit/detail?id={marketId}&applyId={ticketId}`，包含资产字段、数据预览、API 接口文档。详情标题编号是集市编号，列表编号是工单编号，两者不能混用。字段接口按十条分页且源界面反转每页内顺序，因此必须逐页读取再反转页内，不能一次取一百条后整体反转。预览支持字段、运算符、条件启停、AND/OR、增加/删除、查询、重置、排序与横向滚动。接口调用按钮在必填 ID 为空时显示校验提示；未发送有实参的源站接口调用。

同意和驳回弹窗在 1920×1000 CSS 视口均为宽 800、高 355、距顶部 60、直角；标题 16px、标题栏 42、页脚 63，无全屏按钮，允许拖动。资产名为禁用输入框，审批意见/驳回理由为 98px 高文本域，最多 256 字，空白无效。导入工单的提示按真实行为说明仅保存本地意见。

## 实测协议

| 用途 | 源站协议（统一 `/prod-api` 前缀） |
| --- | --- |
| 列表 | GET `/da/assetMartApply/listManager?pageNum=1&pageSize=10&orderByColumn=create_time&isAsc=descending` |
| 申请记录 | GET `/da/assetMartApply/{id}` |
| 审批 | PUT `/da/assetMartApply`，字段 `id/status/approvalReason`，仅静态脚本确认，未提交 |
| 资产详情 | GET `/da/assetMart/{marketId}/{applyId}` |
| 字段 | GET `/da/assetColumn/list?...&assetId={marketId}` |
| 预览 | POST `/da/asset/preview`，只读查询，底层资产编号与分页/筛选/排序 |
| API 文档 | GET `/ds/api/{relatedApi}` |
| 申请人 | GET `/system/user/userDeptTree`，仅导入实际工单使用的人员显示信息 |

状态：1 未授权、2 待授权、3 已通过、4 已驳回、5 已下架、6 已过期。申请周期：1 长期有效、2 自定义。交付形式是逗号分隔的多个值，不能按单个整数解析。日期原值按 UTC+8 转换为毫秒时间戳，数字编号与精确小数按字符串保存。

## 数据与已知边界

导入 21 条工单、7 个来源申请人。最新状态为已通过 13、已下架 5、待授权 1、已驳回 1、已过期 1；工单 2 在分析期间由源站归档为已过期，最终种子以刷新结果为准。

读取 10 组不同集市详情，覆盖 16 条工单；另 5 条下架工单源站无法读取详情，不补造字段或预览。保存 296 个字段实例、149 条预览样本。一个预览的源站总量为 151348，仅保存前 100 条样本，界面明确提示部分记录，筛选与分页只针对保存的样本，不声称已复制全部底层业务库。

源站“河流维表”的资产字段返回 20 个流量统计字段，预览/API 返回河流字段，属于实测源站不一致，按各接口实际结果保存，没有猜测关联并修改字段。API 响应字段使用 `fieldName/fieldComment`，与请求参数的 `paramName/paramComment` 分开映射。没有将来源更新时间推断成审批时间。

## 本项目实现

领域归属 Asset，菜单继续使用 `market/check/index` 与 `asset:market:check`。新增 `/asset/market/check/page|get|applicants|check` 管理接口，分页使用 `pageNo/pageSize` 和 `list/total`；接口权限、原生申请的提供方对象范围与 Guard 均保留。

来源记录存入单租户 `dh_market_approval_work_order`。来源编号和人员键独立，不映射成本地同号账号或资产。历史来源状态不生成本地授权或交付。来源待审操作保存独立本地意见，通过 revision CAS 与同事务领域审计保证并发/回滚；重放导入保留本地意见、版本和删除状态。

原生申请继续委托现有 `MarketApplyService`、`MarketObjectGuard`、`MarketAuditRecorder` 和可靠交付链。已检索 BPM API 与当前原生调用方，本次属于现有资产审批读模型和来源导入，不新增工作流引擎或跨模块数据库访问。JSON 使用框架 `JsonUtils`，DO/Mapper 复用 `BaseDO/BaseMapperX`，没有新建通用技术执行器。

详情快照没有源站凭证。来源 API 文档提供查看，未绑定本地服务前不执行外部接口调用。前端在登录、角色、权限、对象、激活和卸载发生变化时丢弃旧响应并清理详情/弹窗；大整数、小数和日期保持真实线协议。

## 数据库执行

已有库以前置资产市场表为基础，顺序执行 `0225_asset_market-approval-work-order.sql` → `0228_asset_qdata-approval-import.sql` → `0229_asset_market-approval-snapshot-correction.sql`。其他任务的 0226、0227 不属于本功能依赖，按照各自发布计划执行。新库仅导入包含本次三个迁移的最终 `datahub-center.sql`，基线 marker 随并行任务维护至全局最高编号，不再重复导入全量基线升级已有库。

编号迁移已在隔离 PostgreSQL 18 验证首次执行、重复执行、本地意见/删除保留，以及新库全量基线。真实开发库写入前保留 schema 备份、脚本 SHA-256 和原生申请/授权/交付/审计计数；仅执行本功能三个迁移。0225/0228 执行后冻结，截图复核发现的字段页内顺序与 API 路径 `/services/{apiVersion}{apiUrl}` 展示修正通过新增 0229 完成，未原地修改已执行脚本。

## 最终验证记录

- 正常 Maven reactor：审批服务、HTTP 合同、真实 PostgreSQL 并发/回滚/排序与对象 Guard，37/37 通过，0 跳过；原生 `MarketApplyCheckingTest` 223/223 通过。早先无环境变量时真实 PG 四例跳过，最终已显式配置隔离库重跑通过。
- 前端审批领域八个文件 52/52 通过；定向 ESLint、type-aware oxlint、stylelint 通过。浏览器五种视口与交互 54 项，加截图反馈修复 14 项通过，未出现 console/pageerror。业务区视觉 QA 使用独立 fixture harness，已删除临时生产入口。
- 实际 Asset 服务 50003 健康 UP，OpenAPI 四个审批接口注册，网关 48083 匿名访问返回业务 401。完整带登录浏览器联调未执行：当前未获得本项目已登录浏览器会话。已有服务并未被停止；本次启动产物复制到独立目录，避免并行 Maven 编译删除正在启动的 class。
- 运行时暴露出并行资产注册变更缺失 `RegistrationContextApi` Feign 注册，在现有 `RpcConfiguration` 补齐该 API；保留其他线程新增的安全分类客户端，并完成编译与真实应用上下文启动验证。
- 全量前端 quality/typecheck 被并行资产标签/敏感清单代码的语法、类型与 lint 问题阻挡；最终 build 同样被并行资产标签模板语法错误阻挡（较早快照 build 曾通过，不能代替最终结果）。全量测试运行 3083 通过、3 失败，失败位于并行治理质量规则编辑器。没有将这些结果表述成全绿。
- 后端 `run_all.py` 被既有 `0133_modeling_drop-planning-change.sql` 不可变归档校验错误阻挡；单独错误码检查、模块依赖检查通过，DB Switch 架构检查 0 违规。未修改历史归档或降低校验。

执行日志、截图、三次迁移备份 SHA-256 与事务前后核对结果位于 `target/approval-*` 和 `target/qdata-runtime-backup/`。
