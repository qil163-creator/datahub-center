# qData 实时任务配置：2026-11-03 当前参考会话

本轮使用用户已登录的原生 Chrome，新建独立标签进入 `/dpp/integratioTask/stream`，从列表打开既有、未上线的 `stream/edit?id=48`（名称 Flink）。没有创建实时测试任务，没有点击暂存、任务保存、任务检查、运行或调度。仅新增内存节点、打开详情后关闭，结束重新加载，清除了全部未保存节点。离线核对任务47及用户本地画布均未覆盖。原生截图是本轮 CUA 工具内证据，没有声称截图已落盘或像素差分通过。

## 路由和静态证据

- `stream-CM26gkK8.js`：20,713字节，SHA256 `81e11631ca681c65e0945096238317785caf1cf8ec00ae0291c6f2a5f128a9aa`，含 `/dpp/integratioTask/stream/edit` 配置入口。
- `main-BKUMEHhG.js`：3,735,478字节，SHA256 `1a993d3ff7f7f0962e3debd82ebe3d369798161ce667ba702d167243a2fd89f6`；实时与离线 edit 路由均加载 `index-5CNfT-KX.js`。
- 共用设计器根据 `T.path.includes("/stream/")` 向任务表单传 `mode=stream|batch`，节点详情仍传已保存的 `taskType` 和 `taskExecuteType`。不能因路由共用就推断所有节点控件一致；下列11项已实际打开。
- 具体表单原件及SHA见 `/private/tmp/qdata-20261102-forms/manifest.json`，REA静态来源及离线详细矩阵见 [离线观察](qdata-live-reference-20261103.md)。静态包不能替代本轮现场观察。

## 实时可用范围

实时只有 Flink。49项调色板中启用12项：`read-db`、`read-kafka`、`read-tdengine`、`java-script`、`custom-token`、`data-clean`、`field-split`、`dedup-records`、`row-to-column`、`column-to-row`、`value-map`、`insert-db`。其余37项 disabled，包括 Kafka输出、RabbitMQ、RocketMQ、Join、条件分发和子流程。转换父分组 AX 标为 disabled，其内部实际8项仍可拖入并打开，不能把父分组AX当作全部子项不可用。

`read-db` 继续遵守用户原来的排除要求，未打开详情，不声明表输入视觉复刻完成。

## 任务配置

基本信息含任务类目、任务名称、责任人、联系电话（禁用）、任务状态（未上线选中，已上线禁用）、描述256字。执行引擎只有 Flink 单卡，说明“流处理 / 适合处理实时流计算任务”。执行设置：JobManager内存1G、TaskManager内存2G、Slot数量2（步进器）、TaskManager数量2（文本）、并行度2（步进器）、Yarn队列选填（说明默认default）。运行策略：任务优先级高、失败重试0次、重试间隔0分、延迟执行0分、执行策略串行抛弃。只读打开后关闭，未点击保存。

## 11项详情与离线差异

|组件|实时现场控件及差异|
|---|---|
|read-kafka|与离线Flink主区相同：名称/类型，描述256；数据源连接/主题名称；配置参数textarea256，示例`{"group.id":"demo_test"}`；属性字段新增，序号/字段名称/字段类型/JSON解析取值/操作。未单列消费位点或安全参数。|
|read-tdengine|**与离线表快照不同**：连接方式radio仅数据连接；源数据库连接/数据连接类型禁用；数据连接实例禁用/订阅Topic组合框；消费组ID文本/消费起始位置select（默认从最早可消费位置开始，另一项从任务启动后的新数据开始）。属性字段只读7列：序号/英文名称/中文名称/字段类型/是否主键/字段长度/小数精度；无新增/操作。没有表、where、SQL输入。|
|java-script|与离线Flink主区相同：名称/类型、描述、蓝色编译、Java编辑器、输出字段新增，序号/字段名称/字段类型/操作；没有参考窗口控件。未点击编译。|
|custom-token|与离线Flink相同：名称/类型、必填返回token变量id及API Header引用提示、描述、编译、Java编辑器；无输出字段。|
|data-clean|与离线Flink相同：描述、where条件256、规则设置/新增规则、序号/清洗名称/清洗字段/清洗规则/规则描述/维度/状态/操作；底部获取清洗规则。|
|field-split|与离线Flink相同：源字段、分隔符/正则radio、分隔符、包裹字符；字段表序号/名称/类型/长度/精度/格式/去除空格类型/操作；主区无描述。|
|dedup-records|与离线Flink相同：描述、字段新增、序号/字段名称/忽略大小写/操作；底部获取字段。没有时间/窗口/状态/TTL控件。|
|row-to-column|与离线Flink相同，实际宽转长：Key字段文本、字段新增、序号/字段名/Key/输出字段/操作；无描述或窗口。|
|column-to-row|与离线Flink相同，实际长转宽：Key字段选择、分组字段表（新增/获取字段）、新增字段表序号/新增字段/数据字段/关键字/类型/格式/长度/精度/null If/聚合方式/操作。没有窗口或状态控件。|
|value-map|与离线Flink相同：使用字段/目标字段、不匹配默认值、字段值新增表序号/原值/目标值/操作；无描述。|
|insert-db|**与离线Flink不同**：目标连接/类型禁用、实例禁用/表、where条件256、来源/目标字段映射、输出配置。写入模式只有“增量更新”radio（新节点未选中）；单次写入数据1024条，提示不输入默认1000条。没有追加/全量、前后SQL。|

所有节点仅核对空配置及主区，不声明连接解析、Token/Java编译、实时状态恢复、外部输出或端到端任务执行已通过。参考站没有窗口控件，不等于本项目可以采用无界全局状态；本地新增的有限窗口、保留/迟到语义和checkpoint要求必须显式展示于高级配置，并由真实编译/运行验证支持。

## 本地真实执行合同与专项验证

- Token认证在两种模式均为作业启动初始化，`workerState=BATCH`、`batchPolicy=PARTITION`，不使用行数/时间触发；Java行脚本的实时批次仍为`COUNT_OR_TIME`。UI不在挂载时转换旧值，旧等待时间和不适用批次需要用户显式清理。实时Token提示指向表输入/Kafka/TDengine三种合法源，不再引导已禁用的API输入。生命周期和受管启动认证实际验证由后端专项另行记录。
- 实时宽转长仍是无状态逐行转换。去重与长转宽增加显式`streamingWindow`版本1：`kind=TUMBLING`、`timeMode=PROCESSING_TIME`、`sizeMillis=1000..86400000`、`maxRows=1..10000`、`maxBytes=1024..16777216`、`emit=ON_CLOSE`、`latePolicy=PROCESS_ON_ARRIVAL`。窗口按到达算子的处理时间划分；关闭后自动清理状态。没有声明事件时间水印、已关闭窗口重开或迟到补发。预算按每窗每组的累计输入行及编码payload字节计，不是所有键的全局内存字节上限。
- 新高级区域只在用户明确点击启用后写入合同，历史草稿无自动补参。窗内首/末选择保留明确`orderBy/uniqueFields`，空值及ASC/DESC语义与旧批处理一致；完整相同行可去重，唯一键相同但payload不同会失败。Long/Decimal使用无损字符串与BigDecimal比较，不能降为double。
- 正式SQL使用Flink原生处理时间`TUMBLE`/`WindowAggregate`，首末行选择依赖管理员安装并授权`DH_WINDOW_PICK`。函数使用结构化ROW/ARRAY/MAP accumulator，由原生窗口算子checkpoint/恢复/清理；没有在平台JVM执行用户代码。`prepare_etl_functions.py --streaming-window`生成不可变JAR及Flink初始化声明，未安装的环境仍明确拒绝。
- 已实测Flink1.20.3不支持processing-time WindowTopN，因此没有伪造`ROW_NUMBER`实时成功；事件时间普通Catalog clone探测不作为本实现路径。
- [streaming-window-runtime-20261103.json](streaming-window-runtime-20261103.json) 为隔离、只读、无网络容器的真实运行证据。checkpoint在源index3完成后故意故障重启，恢复后首窗仍保留first，下一窗同键输出next-window，结果均INSERT；正式图编译SQL原样执行去重得到4行，seq `9007199254740993`无损；长转宽得到2行，含四种聚合、多关键字末值、日期格式和nullIf哨兵。每项日志、正式SQL及WindowAggregate explain由报告链接。
- 前端实时相关6文件16测通过，随后Token新增“编译”请求父级完整图、只读不提交检查，Token最终4测通过。窗口2测包含手动启用、边界和未知合同。ESLint、stylelint与oxlint专项通过。主编译器窗口专项、旧行列与图编译回归通过；复杂ARRAY/MAP/ROW/BINARY不能经字符串中转而丢失信息，编译阶段明确拒绝。整项目类型/质量及业务cap门禁由根统一执行。

以上新增证据只证明所列原生Flink语义与状态恢复，不扩大为12项全链路业务任务、目标数据库执行或完整像素差分验收。当前参考实时48没有新增持久记录；离线核对47仍按先前约定保留。
