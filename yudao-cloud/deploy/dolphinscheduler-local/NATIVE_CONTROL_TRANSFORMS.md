# 有界转换与子流程原生值验收

`EtlNativeControlTransformFixtureTest` 调用正式 `EtlSubflowExpander` 与 `EtlNativeSqlCompiler` 生成组合 SQL。只有隔离的 VALUES 输入和临时 JSON 文件输出是夹具 DDL，不用手写转换 SQL 替代组件编译。`native_control_transform_smoke.py` 在与现有引擎相同镜像的独立只读容器运行 Java17 CLI，临时目录仅在该容器 tmpfs，不接触现有 DS、平台任务或用户服务。

先用 `run_maven_locked.py -pl yudao-framework/yudao-spring-boot-starter-biz-etl -am -Dtest=EtlNativeControlTransformFixtureTest -Dsurefire.failIfNoSpecifiedTests=false test` 生成 `target/native-control`，再运行 `native_control_transform_smoke.py`。报告、实际 SQL 和日志保存在 `verification/components-20261102-native-control-transform*`。本报告是原生 CLI 范围，不能当成平台任务发布/执行证据。

组合正例消费实际编辑器保存的 `components-20261102-dedup-ui-payloads.json` 三种去重配置，核对：

- 已固定内容哈希的子流程定义、具名输入/输出端口与显式参数，含引号、反斜杠和 Unicode；边界只在原生类型可证明时放行。
- 本地字段删除与 TRIM、整行去重、字符串值映射、Decimal 数值范围、常量、同一原始输入行的多条设值规则、字段改名、保留 NULL 的列转行。
- 末端实际输出行序，包括 NULL LAST、多排序字段、19位 Long 和精确 Decimal。
- 按键忽略大小写与整行忽略大小写，保留选中首条的原始 `MiXeD`/`Ab` 字串；NULL 分组、完全相同的重复行先安全消除。
- 相同排序唯一键但不同 payload 的负例，由原生引擎明确失败；必须匹配真实 RuntimeException/NumberFormatException，不能用 SQL 回显或 MiniCluster 关闭代替根因，Flink 服务日志单独保存。

按键或忽略大小写去重需要用户声明 `orderBy` 与 `uniqueFields`。先对完整投影执行 DISTINCT，之后仍存在的唯一排序键重复会失败，不能由 ROW_NUMBER 任意打散。分组仅对字符串使用原生 LOWER，数值按其原类型分组；被保留的完整行不经过改写。实时去重没有窗口/状态合同，因此仍明确拒绝。

独立排序的次序必须由最外层 INSERT SELECT 的 ORDER BY 消费，不能依赖会被优化掉的子查询排序。排序可直接连接输出或脚本输入；只改变边界合同的子流程端口桥可保序透传。排序后再修改字段、Join 或分支会明确拒绝，请将排序移到对应输出/脚本输入前。脚本物化的输入 SQL 保留顶层 ORDER BY，原有 API→排序→Java 链继续可用。

已有真实值证据精确复用：`graphs.json` 的 DS 两引擎 LEFT Join/NULL 合并与 IF/ELSE 条件，`join-budget.json` 的 Join 预算失败，以及 `components-20261102-transforms.json` 的六个计算、派生、字符串、拆分、序列和行转列场景。不会把这些单测、相似 SQL 或组件内部别名当成新的独立组件实测。
