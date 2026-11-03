# 表输入组件配置与交互验证（2026-11-03）

## 参考与范围

- 参考站点：<https://pro-demo.qdata.tech/dpp/integratioTask/batch/edit?id=18>，以及用户提供的13张表输入截图。
- 本地正式页面：<http://localhost:5999/dataforge/integration/designer/1008>。
- 本次补齐表输入的配置编辑、模式联动、字段选择、校验与草稿回显，不扩大为其他48个组件的验收。
- REA 隔离浏览器访问参考站点时被正常重定向到登录页；没有复制凭据或绕过登录。另通过用户已经登录的 Chrome 查看了参考组件的时间增量、条件新增、空保存校验和原全量模式，未保存参考站点任务。

## 已实现的交互

1. 数据连接选择使用真实连接类型元数据，提供分类、类型、连接三列及数量、搜索、未知值回显、清除、加载失败重试与过期响应隔离。
2. 表模式与 SQL 模式切换保留表分支筛选草稿；SQL 执行参数不携带表分支 WHERE 与字段投影。查询 SQL 使用项目现有 Monaco 编辑器及权威字段解析。
3. 提供全量、ID增量、时间范围增量配置；数值起始值、分批ID起止值与跨度保持十进制字符串。时间条件支持新增、编辑、删除、固定值、时间范围、SQL表达式及游标日期。
4. 新增时间条件默认不选基准类型及运算符，与参考站点一致；空保存提示必填。切换基准类型时不会把 SQL 或数值传入日期控件。
5. 全量模式提供 ID/时间分批、起止、跨度、时间单位、重试次数及间隔；关闭时保留草稿，同一编辑会话中切换分批方式可恢复该方式的字段与范围。
6. 属性字段提供原始序号、备注、类型、真实只读主键、长度/精度排序，并保留原有读取字段选择。排序不改变字段投影或元数据顺序。
7. 所有新增编辑操作遵守只读状态；节点切换后旧条件弹窗、旧元数据请求不能写入新节点。

## 执行能力边界

增量持久游标、成功提交后的游标推进、受管分片检查点与批次重试尚未接入，因此本次没有声称增量或分批能够实际执行。前端显示配置支持提示；DataX、Flink/Spark 与轻量执行器统一在执行前明确拒绝这些活动配置，避免静默转为全量。关闭分批、返回全量及 SQL 模式的非活动草稿不阻止其已有执行路径。

轻量执行器还明确拒绝其尚不支持的 WHERE/字段投影；正式 DataX 路径原有投影、只读 SQL 和 WHERE 安全边界保持。

未启动真实同步、未发布调度、未写入业务目标表，无 SQL 迁移。

## 已运行检查

| 检查 | 结果 |
| --- | --- |
| `pnpm --filter @vben/web-antdv-next run typecheck` | 退出0 |
| `pnpm run build:antdv-next` | 退出0，11个构建任务成功 |
| `pnpm run check:antdv-next:boundaries` | 通过 |
| `node scripts/quality/lint-antdv-next-changes.mjs`，显式指定本次9个TS/Vue文件 | 退出0，含 ESLint、type-aware Oxlint、Stylelint |
| `pnpm exec stylelint apps/web-antdv-next/src/views/dataforge/integration/designer.css` | 退出0 |
| 数据库编辑器、连接选择器、读取配置、真实 Antdv Next 控件、设计器5个 Vitest 文件 | 44项通过 |
| Maven reactor：`EtlTableReadConfigTest,EtlNativeSqlCompilerTest,IntegrationFlowCompilerTest` | 30项通过，0失败、0错误、0跳过 |
| `python3 tools/quality/run_all.py` | DB Switch、迁移、错误码、模块边界全部通过 |
| 两仓库 `git diff --check` | 通过 |

后端命令：

```sh
mvn -pl :yudao-module-dataforge-server -am \
  -Dtest=EtlTableReadConfigTest,EtlNativeSqlCompilerTest,IntegrationFlowCompilerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

前端检查运行环境为 Node 26.13.0，包管理器给出与仓库声明版本不匹配的警告；上述通过结果仅代表本机环境。没有执行全仓 `quality:antdv-next`，避免把同时进行的其他组件改动纳入本组件验收。

## 环境与验收限制

验证期间 IDE 多次启动 DataForge；一次启动缺少 `DatabaseService`，时序与 Maven 重编译重叠，编译完成后的产物具有正确实现及 `@Service`。该竞争原因是有证据支持的推断，未被当时目录快照完全证明。随后通过 IDE 既有配置恢复了服务，健康检查为 UP。

原5999 Vite进程对首页及代理请求均超时，而直连网关正常，已按原端口及原配置重新启动。正式页面的任务保存/重新读取与真实数据同步应与下方隔离组件验证区分，未通过完整正式页面验收前不作完成声明。

REA 组件截图与场景范围见同目录的验证记录；静态夹具验证不等同于后端端到端验收。
