# Antdv Next 活动应用约束

## 适用范围

- 本文件适用于 `apps/web-antdv-next`。同时遵守仓库根 `AGENTS.md`，并以当前 `package.json`、`src/api/request.ts`、路由、store、adapter 和测试为实现事实。
- 优先修改当前业务域的 `src/api/<domain>`、`src/views/<domain>`；只有真实跨业务复用且不依赖领域 API、字典或权限时才进入应用级组件，只有真实跨应用消费者时才下沉 `packages`。

## 请求与传输契约

- 普通平台 API 只使用 `#/api/request` 的 `requestClient`，路径不重复 `/admin-api`，并直接接收已经解包的业务 `data`。不得在页面新建 Axios/RequestClient，也不得重复全局错误提示。
- `baseRequestClient` 当前仍会附加租户相关请求头，因此不能直接用于对象存储或第三方绝对 URL。外部预签名请求必须使用不附加平台身份、租户、语言和加密控制头的明确通道；重定向后的目标也按外部目标处理。
- 后端 `Long` ID 保持 `string | number` 或在 API 边界规范成十进制字符串；路由参数保持字符串，比较和 key 使用 `String(id)`，禁止有损数值转换。API 时间值保持后端真实的时间戳或格式化字符串，`Dayjs` 只存在于 UI 模型。
- 精确小数按十进制字符串往返。现有会转为 JavaScript number 的 formatter 只可用于明确允许近似的展示，不能用于精度敏感值的编辑、计算或回传。

## 路由、权限与单租户运行

- 当前配置为 backend 路由模式。普通菜单来自后端；静态路由只承载隐藏详情、设计器、重定向等辅助页面，且 `meta.authority` 在该模式下不会自动过滤静态路由。
- 页面和按钮显示不构成鉴权。按钮权限码必须与后端 `@PreAuthorize`、菜单 SQL 完全一致；需要页面级拒绝时实现真实路由守卫，同时后端继续执行操作权限和数据范围校验。
- 字典启动加载是非阻塞的，使用方不得在模块初始化时永久快照空结果。选项和标签要能响应数据就绪，并为加载失败和未知值保留可见回显。
- DataHub 业务运行于单租户平台，不在业务 API、状态、缓存或组件 key 中使用 `tenantId` / `visitTenantId`。用户、登录会话、权限或对象变化时，字典、远程选项、列表、详情、标签页缓存及进行中的请求必须失效或重载；旧上下文响应不得写入新页面。通用 system 脚手架继续保留租户开关能力。

## 异步状态、上传与导出

- 搜索建议、级联选项、树选择、预览和详情刷新使用 `AbortController` 或单调请求序号实现 latest-wins。只有当前请求可写入 data/error/loading；组件卸载、弹窗关闭、条件清空或登录会话变化时使旧请求失效。
- 预签名上传必须分清平台请求与外部对象存储 PUT。外部 PUT 不得泄漏平台头；对象上传与平台元数据登记必须按顺序等待，任一步失败都不能发出成功事件，并要避免遗留对象或提供可重试路径。
- 导出从已确认的筛选 DTO 单独组装参数，默认不带表格分页字段；导出按钮权限和后端数据范围必须与列表一致。大文件或异步导出沿用后端任务协议，不在浏览器静默遍历所有分页拼接结果。
- 弹窗和表单提交期间锁定重复操作并在 `finally` 释放；多个并发操作不能共享一个会被旧请求提前清除的 boolean loading。

## 验证

- 最小静态边界检查：`pnpm run check:antdv-next:boundaries`。
- TypeScript/Vue 改动至少运行：`pnpm --filter @vben/web-antdv-next run typecheck`、目标文件的非修复 lint，以及相关 Vitest。
- 路由、请求基础设施、adapter、共享包、构建配置或发布前运行：`pnpm run quality:antdv-next`。该命令包含构建，不能以单独 Vite build 代替 typecheck 或测试。
- 不在脏工作树上运行全仓自动修复；只格式化本任务文件并检查 diff。
