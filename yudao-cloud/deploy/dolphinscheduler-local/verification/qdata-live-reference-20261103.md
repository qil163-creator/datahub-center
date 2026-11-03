# qData 离线任务配置：2026-11-03 当前参考会话逐项观察

## 证据边界

本记录来自当前用户已登录的原生 Google Chrome 会话，由 CUA 打开配置、切换步骤和读取 AX/截图。不是旧报告的复用，也不是本地功能/执行验收。原生 Chrome 没有可用 CDP；CUA 未提供文件保存 API，截图保留在本轮工具记录，未声称落地截图或像素差分通过。

参考任务：Spark `https://pro-demo.qdata.tech/dpp/integratioTask/batch/edit?id=18`；DataX `.../edit?id=46`；独立 Flink 核对草稿 `.../edit?id=47`，名称“复刻核对_Flink_未发布_20261103”。47 使用既有数据清洗类目、保持未上线，只用于核对，未运行、未启用调度、未全局保存节点；结束重新加载清除了未保存画布，**47 本身仍保留，尚未删除**。没有保存或覆盖既有18/46任务。

静态参考包 `/private/tmp/qdata-20261102-forms/manifest.json` 记录 exact URL/SHA256；转换组23项已核验 SHA256 匹配。其 REA 来源 `ev_985b2264fab75b513574562bc5ba9bea498075b0df5b1c2316e80ba48c1e44b6` 是静态源码证据，不能替代当前页面观察。

表输入 `read-db` 依用户要求排除，本轮未打开该组件详情。Spark 其余48项首屏均已打开；API四步均打开；WebService后三步因合法WSDL必填校验无法进入；Join/条件后续字段步骤未在具备有效双上游/下游的图中验证。Flink启用12项中除read-db的11项首屏均已打开。没有在参考站执行脚本编译、解析远端连接、保存配置或运行任务。

## 引擎实际差异

- DataX：只有 read-db / dedup-records / insert-db 可用；现场已观察去重字段表和四选字段冲突弹窗。
- Spark：49项调色板均可用；本轮排除read-db，读取其余48项。
- Flink：原站实际只启用12项：read-db、read-kafka、read-tdengine、java-script、custom-token、data-clean、field-split、dedup-records、row-to-column、column-to-row、value-map、insert-db。write-kafka 实际 disabled。禁用组件显示“功能开发中”。Flink画布还限制只能一个输入组件。
- 用户最新9张截图覆盖了此前“Flink全49项”要求：最终开放范围为 DataX 3 / Spark 49 / Flink 12，Flink token 可用、其余37项在集成设计器不可用。已存在的通用ETL实现保留，不能据其存在向Integration额外开放组件。
- Flink insert-db 主区与Spark基本相同，但写入模式仅追加/增量更新，没有全量、前置SQL、后置SQL。batch默认1024，说明文字写1000条。Kafka输入、TDengine及转换组8项的已观察主区与Spark一致。

## 共有布局

参考屏幕截图约为3648×2080设备像素，模态约1200 CSS像素宽（按2倍缩放估计）。左右内容padding约40，步骤条40高，普通控件32高。普通表格标题为短蓝色竖条+黑字，新增按钮在标题下一行左侧；不是蓝色标题/底部横线/右侧按钮。空表通常约274 CSS像素高；API输出空表更高。表格序号约80，操作约120，派生操作约150。描述大多约100高、256字计数。

字段获取采用“字段冲突处理”模态：已有N个字段、检测到M个新字段，按钮为“增加新的 / 增加所有 / 清除并增加所有 / 取消”。去重、排序、字符串、设置字段值的获取字段为底部橙色按钮；column-map/列转行获取字段在对应表标题下与新增并排。

## 转换、脚本与控制23项

下表均为Spark当前首屏观察；Flink列“同”表示也已打开其Flink首屏且所列内容一致，“禁”表示原站disabled。

|组件|当前主区布局与控件/表列|描述位置|Flink|
|---|---|---|---|
|java-script|名称/类型；蓝色编译；Java Monaco约400高、有minimap；输出字段新增；seq/name/type/op|名称下第二行|同|
|custom-token|名称/类型；返回token变量id全行及API Header变量提示；编译+Java代码；无输出字段|token id后|同|
|data-clean|where多行；“规则设置”+新增规则；seq/清洗名称/清洗字段/清洗规则/规则描述/维度/状态/op；新增弹窗左分类树、右搜索+3列规则卡片；底部获取清洗规则|名称下|同|
|multi-join|3步基本信息/A字段/B字段；首步单列名称、类型、Join模式、A组件、B组件；添加条件；A字段/运算符/B字段/op（无seq）；缺上游时选择禁用|主区无|禁|
|sort-records|字段标题；新增；seq/字段名称/排序方式/op；底部获取字段|名称下|禁|
|field-split|源字段左/拆分radio右；delimiter或regex左/wrapper右；拆分字段seq/name/type/length/precision/format/trim/op|主区无|同|
|field-derive|操作类型左/新增字段右；前缀/后缀两列、分隔符半行；“字段值”表仅seq/name/op|参数后|禁|
|dedup-records|字段；新增；seq/字段名称/忽略大小写/op（每行独立）；底部获取字段|名称下|同|
|row-to-column|key字段半行；字段新增；seq/字段名/Key/输出字段/op；语义宽→长|主区无|同|
|column-to-row|key字段；分组表seq/group/op（新增+获取）；新增字段表seq/新增字段/数据字段/关键字/type/format/length/precision/nullIf/aggregation/op；语义长→宽|主区无|同|
|data-encrypt|算法/密钥两列；明文/密文新增字段两列|参数后|禁|
|data-decrypt|算法/密钥两列；密文/明文新增字段两列|参数后|禁|
|id-generator|序列字段左/生成方式radio右；start/step两列、max半行；UUID模式隐藏序列参数|参数后|禁|
|calculator|字段表seq/结果字段/计算类型/A/B/C/值类型/op|主区无|禁|
|value-map|使用字段/目标字段两列；不匹配默认值半行；字段值seq/原值/目标值/op|主区无|同|
|add-constant|常量字段seq/name/type/default/设为空串/op|主区无|禁|
|number-range|输入/输出两列，缺省值半行；范围标题注明min≤value<max；seq/下限/上界/值/op，原站初始3行，首尾边界禁用|主区无|禁|
|string-operation|seq/输入/输出/trim/case/pad方向/pad字符/op|名称下|禁|
|string-replace|seq/输入/输出/regex/search/replacement/empty/replacementField/wholeWord/caseSensitive/op，横向滚动|名称下|禁|
|column-map|需要选择修改表seq/name/alias/type/length/precision/op，新增+获取；需要移除表seq/name/op，新增|名称下|禁|
|set-field-value|seq/字段名称/替换字段名称/op；底部获取|名称下|禁|
|data-condition|4步基本信息/条件/True字段/False字段；首步名称、True/False组件、输入schema表；次步条件说明、AND/OR、添加条件、seq/字段/判断/比较值/逻辑/op；后续因无有效字段阻校验|主区无|禁|
|sub-pipeline|只有节点名（有业务提示）、子流程select（说明变更不会自动同步）、描述；没有类型行|子流程后|禁|

## 输入与输出23项

普通IO：名称/类型第一排，描述第二排。字段主区、只读元信息及参数顺序如下。

|组件|观察到的当前主区|
|---|---|
|read-odps|连接方式radio数据连接/sql；源连接/类型/实例(项目名)/表；全量/id增量/时间范围增；where；属性表仅seq/英文名/中文名/type。sql模式保留连接元信息、换为SQL解析按钮+SQL代码，隐藏表/读取/where|
|read-excel|上传xlsx/xls≤50MB；起始行/列=1；解析Excel；属性表seq/name/type/日期格式/op，新增|
|read-csv|上传csv≤50MB；解析csv；属性表同Excel；主区没有编码/分隔符|
|read-jsonl|分区“属性字段/上传文件/格式说明”；单jsonl≤50MB、逐行相同结构提示、示例卡；只读属性表seq/name/中文名/JSONPath/type/length/scale，无新增/op|
|read-kafka|数据源/主题两列；配置参数JSON多行；属性表seq/name/type/JSON解析取值/op，新增|
|read-hive|连接方式数据连接/sql；源连接/类型/实例/表；读取全量/id增量/时间范围增；where；属性seq/英文/中文/type/PK/length/scale|
|read-hdfs|源连接/UTF8；path/type(csv)；delimiter；配置参数；seq/name/type/index/op，新增|
|read-mongodb|数据连接radio（无SQL）；源连接/类型/实例/表；全量/id增量/时间范围增；where；schema表。原站scale标签错字“小数经度”|
|read-redis|源连接/数据类型（可搜索select）、Key模式；seq/name/type/解析值/op，新增|
|read-rabbitmq|与Kafka输入同布局：主题名称、配置JSON、JSON解析取值表|
|read-tdengine|数据连接/sql；源连接/类型/实例/表；where；schema表，没有读取模式。Flink已打开且同|
|read-rocketmq|与Kafka输入同布局|
|read-ftp|源连接/UTF8；同步方式单文件/指定目录；同步删除否/是；path/type(csv)/delimiter/config；seq/name/type/index/op，新增|
|insert-db|目标连接/类型/实例/表；where；字段映射；输出配置前置SQL、追加/全量/增量、batch1024条、后置SQL。Flink差异见上文|
|write-odps|目标连接/类型ODPS/实例项目名/表；映射；输出模式追加/全量；batch1024；前/后SQL，无where|
|write-hive|与insert-db近似，但模式无增量|
|write-hdfs|目标连接/UTF8、类型/实例、path/type(csv)/delimiter/compression/config；属性seq/name/type/op可新增；映射；模式追加/全量|
|write-mongodb|目标连接/类型/实例/表、where、映射、模式追加/全量、batch条，无前后SQL|
|write-redis|目标连接/类型/实例、数据类型/Key模式；属性选择checkbox/seq/name/type（无新增）；batch条|
|write-rabbitmq|目标连接/类型/实例、主题/config；属性选择表；batch条|
|write-kafka|目标连接（无类型实例）、主题/config；属性选择表；单次写入字节10000。Flink原站disabled|
|write-rocketmq|目标连接/类型（无实例）、主题/config；属性选择表；batch条|
|write-ftp|同HDFS输出，FTP config示例，字段映射标题原站重复显示为输出配置|

## API / WebService 两项

API实际4步：基本属性/输入参数/Header/输出字段（不是3步）。第1步名称/类型两列；方法select+URL、返回格式、JSONPath+加号、分页radio、读取radio；无主区描述。第2步添加参数，表参数字段/参数值/op，无seq；底部switch可切自定义Body字符串代码模式，再有Body请求参数switch。第3步添加Header头，表header/参数值/op，初始Accept=`*/*`，参数值textarea256字计数。第4步添加字段/删除字段/从结果JSON中自动分析，表选择/seq/name/type/format/default/op。

WebService也是4步，首步单列名称、只读类型、WSDL URL（自动解析提示）、请求接口（未解析disabled）、请求方式（disabled）、描述（末尾）。点击Next触发URL/接口/方式必填校验；未提供或伪造WSDL、未触发网络解析，故后3步不得记为现场完成。

## 本地实现必须保留的真实契约与已知未等价内容

- 去重逐字段`ignoreCaseFields`显式数组优先于旧global；首次编辑从global初始化所有字符串去重键，数值字段不可勾选；KEY_FIELDS或折叠大小写仍需明确排序/唯一性契约。
- Join新增左右独立投影、类型转换和连接条件关系需编译器真实处理；不能只改表格。`leftNodeNo/rightNodeNo`成对绑定已连接上游；缺省继续原连接顺序。缺省投影继续旧图合并。
- 条件新增`conditions/conditionRelation`与每分支`fields`需后端分支SELECT投影；缺省继续旧单条件和全部字段。旧多分支保留，不压缩成2个分支。
- 受管脚本资源、批次、授权、输出空值、历史参数保留在高级区。蓝色编译入口请求完整图integration/check，不能调用旧ETL独立compileJava或把浏览当作编译成功。
- 受管加密使用secretKeyRef，不能为视觉复刻恢复明文密钥。
- 子流程保持固定发布版本、摘要、端口、权限/身份上下文和显式参数绑定；参考简单选择框不等于允许静默跟随最新版本。
- 行列组件参考命名与旧本地mode相反。仅新建默认按参考语义并启用 `rowColumnVersion:2`，历史mode及旧参数不变。V2 unpivotMappings 保存逐行来源、Key字面值与输出列；V2 pivotMappings 保存逐行来源、目标、关键字、类型/格式/长度/精度/nullIf及Sum/Average/Minimum/Maximum。空聚合仍需确定全序取最后匹配值。日期格式只用于日期时间类型，nullIf在类型转换前匹配；同一目标的多关键字须使用一致类型与聚合方式。非法配置明确拒绝，历史节点只在用户显式升级时转换。
- 清洗规则卡片来自真实治理API，只有启用且engineSupported规则可选；不能伪造参考40条规则或6个维度。whereCondition只允许后端受限谓词，不是任意SQL。

本轮前端专测/类型检查结果由对应验证日志记录；本文首先是参考观察矩阵，不构成全部1:1像素验收或49项真实执行验收。

## 本轮新增的实际验证

- 用户上传50MiB：FileSchemaReaderTest 10、IntegrationFileSchemaServiceTest 6、UserUploadReadLimitTest 3，共19测，0失败、0跳过。包含完整50MiB CSV解析、精确读取、+1超限、owner/managed路径/完整性，以及受管生成20MiB仍拒绝。
- 行列编译器V2专项4测、GraphCompiler旧回归8测、IntegrationSchemaService 10测通过。编译不可用时保留局部字段名并保持unknown/不完整；正式shape提供VARCHAR长度与DECIMAL精度/小数位。
- [row-column-v2-runtime-20261103.json](row-column-v2-runtime-20261103.json)：正式编译器SQL在独立只读、无网络容器分别用Spark/Flink原生CLI执行。宽转长21行、聚合/日期/nullIf 2行、多关键字确定末值2行，两引擎共6场景精确结果全部通过，容器均已移除。没有操作平台任务、DS实例或外部业务表。
- 前端此前7文件26测通过，新增Pivot3测与Flow3测通过。最终全局typecheck由根统一执行；这些结果不构成49项全图执行或1:1像素差分验收。
