# Yudao DataHub 前端仓库约束

## 适用范围与事实来源

- 本仓库是 `yudao-ui-admin-vben` 前端。开始工作前先查看 `git status`，保留用户已有改动，不覆盖、不回退无关文件。
- 业务页面与 API 默认只修改 `apps/web-antdv-next`。其他 `web-*` 应用是脚手架变体，除非用户明确要求，不同步实现相同功能。
- 修改 `apps/web-antdv-next` 时还必须读取其目录内的 `AGENTS.md`；根规则与应用规则共同生效，应用规则只补充当前活动应用的真实运行边界。
- 配对项目的版本化 Skill 唯一源位于兄弟后端仓库 `yudao-cloud/.agents/skills/yudao-datahub-development`；不要在前端仓库复制出第二份。即使 Skill 未安装，本文件和应用级 `AGENTS.md` 仍独立生效。
- Node、pnpm、依赖版本、脚本和类型规则以当前 `.node-version`、`package.json`、workspace 配置及源码为准，不把 README 或旧页面中的版本和兼容写法当作当前事实。
- 使用 pnpm；不得使用 npm/yarn 安装依赖，不手工编辑 lockfile。

## DataHub 单租户边界

- 数据中台业务（asset、modeling、dataforge、supply、governance、market）不建模租户，不提交、读取或依赖业务租户字段；平台级命名范围使用 `GLOBAL`。
- 登录身份、角色权限和部门/对象数据范围继续生效。防止旧响应回写时跟踪用户、登录会话、权限、对象和组件生命周期，不再依赖租户变化。
- system 登录、租户管理及共享请求客户端属于通用脚手架，保留配置开关；禁用开关后不得发送租户请求头。

## 前端实现约束

- UI 组件使用 `antdv-next`；禁止新增 `ant-design-vue` 或业务页面 `antdv-next/dist/**` 导入。集中组件 adapter 是当前懒加载例外，修改它时必须扩大类型检查和构建范围。
- Vue 新代码使用 TypeScript 与 Composition API。跨功能使用 `#/...`，共享包使用 `@vben/...`；类型导入使用 `import type`。
- 普通业务请求统一走 `#/api/request` 的 `requestClient`。它已经处理 token、语言、租户头、统一错误及 `{ code, data, msg }` 解包，调用端不要再访问 `result.data`。
- `baseRequestClient` 只用于令牌刷新、验证码、预签名上传等现有特殊协议，调用方自行承担响应、请求头、状态码与错误处理。`silentError: true` 只用于有明确本地兜底的可降级请求，不在页面重复全局错误 toast。
- 对对象存储、第三方服务等绝对 URL 发请求时不得携带平台的 `Authorization`、`tenant-id`、`visit-tenant-id` 等内部头；使用特殊客户端前必须核对其拦截器，而不能根据客户端名称推断安全性。
- 前端只访问网关；Controller 和前端 API 路径都不要重复写 `/admin-api`。分页遵循 `pageNo/pageSize` 与 `list/total`。
- 标准 CRUD 优先使用当前 `#/adapter/form`、`#/adapter/vxe-table`、`useVbenModal` 与 `TableAction`；工作台、向导、详情和可视化编辑器按页面性质选择实现，不机械套 CRUD 模板。
- 表格必须配置无损且稳定的 `rowConfig.keyField`；编辑时按 ID 重新获取详情，异步提交/删除必须在 `finally` 解锁，批量删除成功后清理勾选。弹窗销毁或显式重置表单、loading 与局部状态；异步选项防止旧响应覆盖新选择。
- 字典字段复用项目字典与 `CellDict`，并给 `getDictOptions` 传与 DTO 一致的值类型；提交 code/value 而不是 label。非系统字典的领域枚举使用单一 typed 映射，不伪造 `DICT_TYPE`。表单 Schema 使用当前已注册组件和 Zod 4 接口；新条件联动使用 `dependencies.resolve`，日期/时间 UI 值通过 form-level typed `codec` 转换，不新增已弃用兼容 API。
- 当前字典在路由守卫中异步非阻塞加载，不能把模块初始化时得到的空数组当作最终结果。字典或远程选项必须有加载中、未知值回显及失败语义；登录用户、会话或对象变化时，敏感的字典、选项和页面缓存必须失效并按新上下文重载。
- 普通管理菜单和路由由后端动态下发。不要为普通 CRUD 再创建静态菜单路由；菜单 `component` 必须能映射到 `views/**` 页面。
- 静态 route modules 只用于详情、设计器、重定向等辅助页面，并设置唯一 name/path、隐藏菜单、正确 `activePath` 与返回逻辑；它不是后端菜单的组件候选目录。
- 当前活动应用使用 `accessMode: 'backend'`；该模式不会通过静态路由的 `meta.authority` 过滤页面。不得把 `meta.authority` 当作页面鉴权或安全边界，需要拒绝访问时必须有真实守卫，并始终依赖后端接口权限与数据范围。
- `TableAction.auth` 权限码必须取自当前后端 `@PreAuthorize` 和菜单 SQL，不能凭命名猜测。权限数组是 any-of/OR；需要 AND 时显式计算。前端隐藏按钮不是鉴权或行级数据权限。
- 优先按 `@vben/common-ui`、`@vben/hooks`、`@vben/utils`、`#/adapter`、`#/components`、当前功能组件的顺序检索复用。只有存在真实跨应用消费者且不耦合业务 API、字典、权限或领域模型时才下沉 `packages`。
- 不向 `.env`、源码、示例、日志或提交内容新增真实网关账号、密钥、令牌或连接信息。
- 搜索、联动选项、预览和详情等可能并发的异步请求必须采用取消或 latest-wins 语义；旧响应、已卸载组件的响应不得覆盖新状态，loading 也不能被较早请求提前关闭。

## 无损 API 契约

- 后端 `Long` ID 按数值大小可能返回 JSON `number` 或十进制 `string`。新增/修改契约使用 `string | number` 等无损类型，或在 API 边界统一为十进制字符串；禁止对 ID 使用 `Number()`、一元 `+`、`parseInt()`、算术运算等有损转换。路由参数保持经格式校验的字符串，比较、Map key、树/表 key 可使用 `String(id)` 规范化。原生 `bigint` 未配置专用序列化时不得直接放入 JSON 请求体。
- API DTO 使用真实 JSON 原语而不是 `Date`/`Dayjs`。必须核对后端 VO 与 `@JsonFormat`：当前默认 `LocalDateTime` 为毫秒时间戳，有注解才按指定字符串格式；`Dayjs` 只留在 UI/表单模型并在边界转换。
- 金额、比例、度量等精度敏感的 `BigDecimal` 应由后端以十进制字符串传输，前端 DTO 和提交值保持字符串。不得先经过 JSON number、`Number()`、`parseFloat()` 或普通浮点格式化再转回字符串；需要精确计算时使用经项目确认的十进制方案或交给后端计算。
- 代码生成结果是候选脚手架。Antdv Next 生成后必须专项复核 Long ID、精确小数、日期时间、Req/Resp 字段差异、分页与数组参数、每个接口返回类型、输出到活动 app 的路径以及旧 UI 导入；类型检查通过不等于运行时契约正确。

## 跨端与 SQL 任务

- 跨端变更必须同时核对后端路径、HTTP 方法、Req/Resp DTO、分页、Long/时间、枚举/字典、权限码和菜单组件路径。
- 导出必须使用明确的导出参数契约和与列表一致的筛选、权限及数据范围；默认移除 `pageNo/pageSize`，只有产品明确要求“导出当前页”时才传分页参数。不得把表格内部状态对象不经筛选直接传给导出接口。
- 预签名上传只有在对象写入和平台文件元数据登记都成功后才算完成；后一步失败必须向调用方返回失败并具备重试或清理说明，不能后台静默登记后提前报告成功。
- 如果任务需要修改兄弟仓库 `yudao-cloud` 的数据库，必须读取并遵守其根 `AGENTS.md`。
- 任何新增或修改 SQL 都必须在后端 `sql/postgresql/migrations` 新建带全局四位顺序前缀的增量文件，例如 `0001_...sql`、`0002_...sql`、`0010_...sql`；不得只改全量基线。生成器的未编号 `sql/sql.sql` 只能作为整理迁移的输入，不能直接交付或执行。最终说明必须按编号列出 SQL 执行顺序。

## 验证与交付

- 前端验证优先针对 `@vben/web-antdv-next`：`pnpm --filter @vben/web-antdv-next run typecheck`、改动文件的非自动修复 lint、聚焦 Vitest，再按风险运行 `pnpm run build:antdv-next`。
- 仓库级完整门禁统一运行 `pnpm run quality:antdv-next`；其中的边界检查、lint、typecheck、Vitest 和构建均不得用另一项代替。局部开发可以先运行更窄的子命令，但交付时要说明未运行的门禁及原因。
- `build` 不能代替 typecheck。不要在脏工作树上无差别运行会修改全仓文件的格式化命令。
- 最终说明必须列出实际执行的检查、结果、未执行项及原因。
