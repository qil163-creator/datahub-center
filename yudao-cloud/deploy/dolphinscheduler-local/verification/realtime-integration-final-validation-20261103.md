# 实时集成配置与引擎范围核验

本轮按用户最新修正将实时任务限定为 Flink。继续使用已获用户认可的 `pixel-perfect-reference-ui-zh`，参考站与 REA 静态原件和页面现场记录见 [实时逐项记录](qdata-realtime-live-reference-20261103.md)、[离线逐项记录](qdata-live-reference-20261103.md)。表输入主区视觉按用户原要求排除；工作区后来出现的独立表输入改动不作为本轮复刻结果。

## 产品与实际服务

原 IDE `DataforgeServerApplication` 重新启动后，经原网关接口实际读取：

| 模式 | 引擎 | 可用 | 不可用 |
| --- | --- | ---: | ---: |
| 离线 | DataX | 3 | 46 |
| 离线 | Spark | 49 | 0 |
| 离线 | Flink | 12 | 37 |
| 实时 | Flink | 12 | 37 |
| 实时 | DataX / Spark | 0 | 49 |

响应记录：[integration-engine-capabilities-20261103.json](integration-engine-capabilities-20261103.json)。`PARTIAL` 在页面统一显示“可用”，配置约束继续由后端执行。实时非 Flink 在创建、保存、检查、发布与运行侧拒绝；历史配置不在页面挂载时被静默迁移。

Flink 两种模式启用相同 12 个组件：表输入、Kafka 输入、TDengine 输入、Java 脚本、自定义 token、转换、字段拆分、去重、行转列、列转行、值映射、表输出。其余 37 个组件关闭。实时 TDengine 使用 TMQ 配置；表输出只显示增量更新，新节点默认 `dataSyncType=2` 和批次 1024，已有追加配置保留原值并明确校验。

## 真实执行证据

- [窗口及恢复](streaming-window-runtime-20261103.json)：正式编译 SQL 执行去重与多聚合，处理时间双窗口；完成 checkpoint 后故障，恢复源索引 3，输出两条独立窗口结果。不是事件时间迟到补发。
- [实时 PostgreSQL upsert](flink-verified-jdbc-upsert-20261103.json)：实际更新和删除；缺主键、错误目标身份及空键拒绝且不写入。不是持续 CDC 全链验收。
- [token 启动认证](flink-token-streaming-runtime-20261103.json)：STREAMING 环境有限 JDBC 样本的正负场景、凭据隔离与退出清理；不据此声明持续输入、token 刷新或恢复全部通过。

统一后端回归 130 个用例通过，无失败、错误或跳过。最后复杂类型边界专项 5 个用例通过；其覆盖与上述回归部分重叠，不相加为独立用例数。后端 `tools/quality/run_all.py` 的迁移、错误码、模块和 DB Switch 检查通过。

## 页面与检查边界

REA 本地真实组件夹具在与参考相同的桌面宽度检查了实时 11 个组件（除表输入），无 page-error；脚本等待 Monaco 实际渲染后另行核验。另检查 390px 窄屏表单、底部操作与滚动。夹具没有后端请求或保存，这些截图不是管理 API 发布/运行验收，也不声明逐像素完全相等。

Node 24 应用类型检查与生产构建通过，本次原 28 个集成测试文件共 209 项通过；明确排除并行新增的 `table-input-*` 与 `database-connection-picker` 测试。日志分别记录在本机 `/tmp/datahub-realtime-current-typecheck.log`、`/tmp/datahub-integration-realtime-final-build.log`、`/tmp/datahub-realtime-requested-scope-tests.log`。完整 `quality:antdv-next` 已运行，但未全部通过：治理关联列表的 3 个测试失败；并行表输入改动落盘过程中还出现过依赖缺失/测试替身问题、新批读参数及真实表格冻结列测试失败。这些结果不能标为完整门禁通过。原始记录保留于 `/tmp/datahub-realtime-quality-final.log` 和 `/tmp/datahub-integration-realtime-final-combined.log`。

## 部署与未完成项

正常环境当前没有可证明的持续源 Catalog、任务专属脚本资源和新窗口函数完整授权。本轮未把隔离环境的私有绑定覆盖到正常环境，也未将普通 JDBC 全量源标为持续源。`DH_WINDOW_PICK`、官方 JDBC/驱动及校验工厂须按实际受管环境安装后使用，见 [JDBC 部署说明](../VERIFIED_FLINK_JDBC_UPSERT.md)。平台 SQL 初始化禁止原始 `ADD JAR`；函数走 `CREATE FUNCTION ... USING JAR`，JDBC 工厂走受校验的工作节点类路径。

离线 Spark 仍有明确缺口：REST 时间增量缺全任务提交后的持久游标协议；ODPS 自定义 SQL/hooks、Hive 覆盖/hooks/批次生命周期以及 FTP 同步删除不能宣称完整实现。ODPS 已有的受权全量覆盖本身已实现。具体见 [IO 高级执行调查](io-advanced-execution-gap-audit-20261103.md)。因此本报告不将 49 个组件所有参数描述为已一比一执行验收。

TDengine 离线 JDBC adapter 尚未完成实际服务器和独立 JDBC TLS 策略验收；现有 TMQ `useSsl` 不能作为其 TLS 证明。对应 `sparkConnectorInstalled` / `flinkBoundedConnectorInstalled` 默认继续关闭，不部署该未经证明的 adapter。

本轮补充的 [RabbitMQ 实际运行尝试](spark-rabbit-bounded-runtime-20261103.json) 为 `ENVIRONMENT_BLOCKED`、`runtimeAcceptance=false`：官方镜像在 AMQP 启动前出现 Erlang cookie 权限错误，未提交 Spark 作业。专属容器、网络和秘密文件已清理。RabbitMQ、RocketMQ、TDengine 的额外 Spark 端到端运行均不列为已通过；Spark Kafka 的实际运行证据独立有效。

本轮实时配置不新增数据库迁移。原用户前端 5999 服务保持运行；临时视觉夹具和临时测试资源由本轮清理。
