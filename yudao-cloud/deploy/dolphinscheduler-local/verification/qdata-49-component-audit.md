# qData 49组件配置覆盖与剩余差异

更新：2026-11-01T22:34:24.850987+08:00

该表来自qData原始前端bundle静态检查与当前本地源码快照。源bundle只证明控件、参数和前端规则；逐行列出独立测试证据，**不代表49项均完成浏览器交互、像素对照或真实引擎执行验收**。

qData调色板白名单：SPARK49、FLINK12、DATAX3（read-db、dedup-records、insert-db）。这不是当前后端执行能力清单。

|#|组件/本地code|源bundle与引擎|当前已补|仍有差异/缺项|执行依赖|证据|
|---|---|---|---|---|---|---|
|1|表输入组件 / read-db|tableForm-C5T-zRxm.js / SPARK,FLINK,DATAX|平台连接/表搜索、字段勾选与完整元数据、SQL解析、右键输入输出字段只读查询。|资产表入口、分批ID/时间读取、结构化时间增量条件和读重试未对齐；当前仅全量安全SELECT原生执行。|UI元数据查询可独立补；增量/分批/读重试须真实执行协议|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_PLATFORM_DS_JDBC|
|2|ODPS输入组件 / read-odps|odpsForm-BHYw3qFG.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。平台连接/SQL文本/读取模式。|ODPS表搜索与实际字段自动发现、SQL解析及结构化时间增量条件未补；凭据安全引用适配阻止执行。|表/SQL元数据需受管连接；ODPS凭据适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|3|Excel文件输入组件 / read-excel|excelForm-6E9BirG5.js / SPARK|普通上传登记fileId、已上传、1基首数据行/首列、真实Excel解析、类型未知警告、字段增删/格式、变更后重新解析保护。|源后台未捕获，无法证明远端解析算法等价；类型来自采样而非权威DB契约，DS文件执行尚不支持。|文件元数据可独立补；不能放开任意URL或冒称DS已能执行|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_FILE_METADATA_API|
|4|CSV输入组件 / read-csv|csvForm-BgGh99tM.js / SPARK|普通上传登记fileId、已上传和真实CSV解析、首数据行/首列/表头、文本字段与共享属性编辑、变更后重新解析保护；设计器按配置选择上传解析模式或原受管资源模式。|上传解析限UTF-8、逗号与双引号，不等同参考全部可选编码/分隔符；CSV无类型声明，长度/精度未知；fileId不会自动获得DS受管执行绑定。|真实文件API采样辅助与已验DataX受管CSV读写分别验证，授权路径分开，禁止将上传附件直接当作工作节点资源。|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST,LIVE_FILE_METADATA_API,LIVE_NATIVE_DATAX_CSV|
|5|JSONL输入组件 / read-json|JSONL-B_sFCJsm.js / SPARK|专用JSONL普通上传+真实逐行采样解析，保留原JSON数组配置，字段路径/长度/精度/变更失效；未知类型不默认。|嵌套对象不自动展开、类型仅采样；源JSONL后端处理未证实，DS JSONL执行未支持。|需真正JSONL安全解析；DS JSONL执行未支持|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_FILE_METADATA_API|
|6|Kafka输入组件 / read-kafka|kafkaForm-D-OLKiRe.js / SPARK,FLINK|平台连接引用、topic、配置JSON、属性新增删除/取值、三种获取模式与字段顺序。|持续源真实受管Kafka适配尚未支持；消息字段声明不代表已验证实际载荷。|平台引用与字段编辑可补；Kafka持续源执行需受管连接适配|REFERENCE_STATIC,LOCAL_SOURCE|
|7|Hive输入组件 / read-hive|hiveForm-B08U2D1l.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/表文本/SQL/读取模式。|表目录搜索与自动元数据、SQL解析、结构化时间增量表未补；Hive运行绑定未验。|Hive运行插件/安全catalog尚待验收|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|8|HDFS输入组件 / read-hdfs|hdfsForm-BAlh7naL.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。路径/类型/编码/分隔符/Hadoop配置/字段索引。|外部HDFS连接与真实读取未验；当前仅管理员显式授权的无凭据绑定编译。|DataX无凭据HDFS已编译未连外部实测；UI辅助可补|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|9|API输入组件 / rest-api|apiForm-CZNsp4ck.js / SPARK|复用既有HTTP分页/字段配置；平台字段分析可展示局部声明。|仍缺参考三步骤布局、时间增量参数/游标与自动分析后字段冲突三模式；受管外联与SSRF执行边界不能仅靠按钮补齐。|只读响应schema需受管请求/SSRF边界；时间增量不能仅加控件|REFERENCE_STATIC,LOCAL_SOURCE|
|10|MongoDB输入组件 / read-mongodb|mongoDBForm-DsD54Htb.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/集合文本/SQL文本/增量基础控件。|集合目录自动读取/字段发现、SQL解析和结构化时间增量未补；DataX只支持受管匿名全量读取。|匿名受管DataX全量已编译；SQL/增量未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|11|Redis输入组件 / read-redis|Redis-Fu70B1Rk.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接、数据类型、Key模式与解析取值。|Redis各数据类型的专用参数/解析值语义未逐项对齐，执行适配未支持。|Redis插件/受管执行适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|12|RabbitMQ输入组件 / read-rabbitmq|RabbitMQForm-CgU2zmWE.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/topic/配置JSON/JSON取值。|真实消息载荷发现与RabbitMQ执行插件/绑定未支持，属性仅声明。|RabbitMQ执行插件/绑定未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|13|TDengine输入组件 / read-tdengine|TDengine-G_zbUj-2.js / SPARK,FLINK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/表SQL文本/Topic/groupId/offset。|订阅Topic目录/解析、消费组冲突继续确认、资产表与字段自动发现未补；批/持续执行适配未支持。|TDengine批/持续订阅需受管适配；不能伪造实时|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|14|RocketMQ输入组件 / read-rocketmq|RocketMQ-BVQtKDa2.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/topic/JSON取值。|真实消息载荷发现与RocketMQ运行绑定未支持；配置JSON合法不表示可执行。|RocketMQ执行插件/绑定未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|15|FTP输入组件 / read-ftp|FTP-dJLqXkJ1.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。编码/路径/类型/分隔符/FTP配置与字段索引。|FTP安全凭据引用和真实运行仍未支持；不可内联密码解锁插件。|FTP插件有内联凭据要求，安全引用适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|16|WebService输入组件 / read-webservice|webServerForm-CG6qGLQg.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。URL/操作/命名空间/参数JSON与JSON取值。|WSDL解析、服务/端口/端点回显和三步骤结构仍缺；通用HTTP配置不等同SOAP服务契约。|WSDL解析需受管外联安全边界，执行通道未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|17|执行Java脚本 / java-script|java-Ba-9YCw3.js / SPARK,FLINK|参考专用编辑器增加输出字段表/类型、编译检查与错误展示，隔离草稿保存。|编译问题行列定位及qData EtlBase上下文仍不等价；DS Java脚本执行未适配。|编译检查已有真实API；DS脚本执行适配未支持|REFERENCE_STATIC,LOCAL_SOURCE|
|18|自定义token认证 / custom-token|customAuth-DlAYqY9M.js / SPARK,FLINK|返回token变量ID、脚本文本及校验；节点元数据对未知保持局部。|代码编辑器工具/模板、认证运行上下文与输出schema联动未补；必须先有受管秘密/隔离执行。|秘密引用/隔离运行/DS脚本尚未适配，不得仅补按钮|REFERENCE_STATIC,LOCAL_SOURCE|
|19|转换组件 / data-clean|cleanForm-Dzc6JrFd.js / SPARK,FLINK|复用既有本地清洗/治理规则查询与排序；字段分析标记局部。|参考逐字段清洗规则、数据元/规则选择弹窗、字段合并与上下游绑定布局仍不同；原生规则仅子集。|治理规则真实服务已有；原生SQL仅部分规则可执行|REFERENCE_STATIC,LOCAL_SOURCE|
|20|Join组件 / multi-join|joinForm-DBzberRK.js / SPARK|批INNER/LEFT连接字段、预算、实际原生SQL输出值验证。|参考A/B组件选择、多条件运算符表、三步骤A/B投影别名/类型/说明与至少一字段保护仍未完整对齐。|仅批原生Join子集实测；多条件/选列契约需编译器支持|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|21|排序记录 / sort-records|orderConfig-BrDK3zGY.js / SPARK|参考字段表/元数据、上游获取三模式、拖拽与删除保护、方向与顺序优先级。|实时全局排序的窗口边界未支持；排序结果语义仍按本地原生子集，不宣称全部参考参数已执行。|批排序支持；实时全局排序需要窗口边界|REFERENCE_STATIC,LOCAL_SOURCE|
|22|字段拆分 / field-split|fieldSplit-D_qRtSnn.js / SPARK,FLINK|参考输出表/类型/长度/精度/格式/trim、重复名校验、顺序与新增保护；原生字面拆分实测。|正则/包裹符及字段类型强制转换等未适配时检查拒绝，不能将可保存参数视作已执行。|字面拆分实测；regex/包裹符未原生适配|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|23|字段派生器 / field-derive|fieldBuilder-Bskuy7xg.js / SPARK|新参考派生编辑器：来源字段/生成规则类型与动态参数校验，保留原配置协议。|表达式/哈希/正则提取等仅配置子集，参考获取合并/完整生成规则表仍需核对；原生执行仍按编译器允许子集。|原生仅部分派生支持；其余不能只补选项|REFERENCE_STATIC,LOCAL_SOURCE|
|24|去除重复记录 / dedup-records|dedupFilter-DjtZ0zrG.js / SPARK,FLINK,DATAX|获取字段三模式、字段选择/去重校验，保留旧去重配置。|参考逐字段表和每字段忽略大小写、元数据仍未完整对齐；key首条/实时有状态去重未支持。|当前原生仅批整行去重；按key首条与实时需排序/状态边界|REFERENCE_STATIC,LOCAL_SOURCE|
|25|行转列 / row-to-column|rowToColumn-BCTPsCze.js / SPARK,FLINK|保留既有ROW2COL配置与已保存mode，未做破坏性迁移。|高风险：qData该标签实际宽表拆多行，与本地命名相反；输入Key/Value逐行映射仍未对齐，ROW2COL执行拒绝。|必须适配语义并保留历史mode；当前ROW2COL执行仍拒绝|REFERENCE_STATIC,LOCAL_SOURCE|
|26|列转行 / column-to-row|columnToRow-CKofAydD.js / SPARK,FLINK|保留既有COL2ROW协议，列转行原生结果和UUID混合图已实测。|高风险：qData该标签实际键值多行转宽表；分组/聚合/目标元数据配置不等价，不可交换旧mode。|当前COL2ROW已实测；复刻参考不能破坏既有mode|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|27|字段加密 / data-encrypt|cryptoBox-CcZemN77.js / SPARK|既有算法/输入输出字段/秘密引用配置与保留源字段。|参考单算法单密钥布局及获取交互仍不同；DS原生加密未支持，不能保存内联秘密。|DS原生加密未支持；密钥必须走受管秘密绑定|REFERENCE_STATIC,LOCAL_SOURCE|
|28|字段解密 / data-decrypt|decryptForm-Dkui5gjq.js / SPARK|既有可逆算法/输入输出字段和保留字段控件。|参考单字段布局/密钥约束/获取交互仍未对齐；DS原生解密和安全秘密绑定未验。|DS原生解密未支持；不能在配置中扩散秘密|REFERENCE_STATIC,LOCAL_SOURCE|
|29|ID生成器 / id-generator|addField-CsxIveM8.js / SPARK|参考ID模式、最大值/起始/步长联动校验和说明，数值字符串精确比较；UUID真实引擎验证。|有序全局序列仍需确定排序/并发协议，非UUID模式不能沿用UUID执行结果作为证明。|UUID真实实测；全局序列需确定排序协议|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|30|计算器 / calculator|calcWidget-C-pW10ZM.js / SPARK|参考计算类型/依赖ABC字段/输出类型及新增删除校验的新编辑器。|日期/字符串计算枚举实际运行与qData每种函数等价尚未验；原生calculator执行仍未支持。|原生calculator尚未支持，UI可先精确表达但不可运行假成功|REFERENCE_STATIC,LOCAL_SOURCE|
|31|值映射 / value-map|valueMapping-D7WZd3Ip.js / SPARK,FLINK|参考映射表拖拽、重复原值/未填行阻止新增和未匹配策略。|仅原生字符串映射子集，不保证所有参考字段类型/匹配细节等价。|原生字符串映射子集支持；类型边界需保持|REFERENCE_STATIC,LOCAL_SOURCE|
|32|增加常量 / add-constant|addConstants-CpvR33Hj.js / SPARK|参考字段表/类型/默认值/空串、重复名与未填行阻止新增、排序/删除保护。|字段冲突三模式和日期/特殊类型转换仍需核对；仅已验证常量子集可执行。|部分常量已真实实测；日期/特殊类型需编译契约|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|33|数值范围 / number-range|numericRange-DzlNmzo0.js / SPARK|参考数值区间表、上下界/重叠与重复值联动验证，精确十进制比较与新增保护。|默认值/边界细节仅按现有原生协议，未覆盖所有类型真实引擎逐值验收。|已有原生数值范围子集；边界语义必须逐值验收|REFERENCE_STATIC,LOCAL_SOURCE|
|34|字符串操作 / string-operation|stringOperation-CO5w1uaC.js / SPARK|上游获取三模式、来源字段选项、拖拽、重复输出校验与规则说明已补。|原生填充等选项未适配时仍拒绝，当前trim/大小写真实验收不代表全部选项。|trim/大小写原生实测；填充尚未适配|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|35|字符串替换 / string-replace|stringReplace-CWdIAQPQ.js / SPARK|获取三模式、拖拽/重复输出验证，使用字段值替换的勾选与来源选择联动已补。|正则/忽略大小写/全词与字段值替换等需要原生适配，当前仅大小写敏感字面替换子集。|原生仅大小写敏感字面替换；其他选项未适配|REFERENCE_STATIC,LOCAL_SOURCE|
|36|字段选择、修改 / column-map|fieldSelectAndmodificat-DTBa0B5X.js / SPARK|专用字段名/别名/类型/长度/小数精度选择表与移除字段列表、上游获取模式；旧正则配置保留。|类型/长度/精度转换未实现时需保持未知并检查拒绝；仅改名/删除形态不代表全部执行等价。|类型/长度/精度修改需编译和元数据契约，不能只加显示列|REFERENCE_STATIC,LOCAL_SOURCE|
|37|设置字段值 / set-field-value|setFieldValues-B9zy9XNr.js / SPARK|来源字段选择、获取三模式、拖拽、重复目标/未填替换项阻止新增。|仅现有原生字段替换子集；参考输出source归属与复杂类型仍需逐项验证。|部分原生字段替换支持；UI元数据获取可独立补|REFERENCE_STATIC,LOCAL_SOURCE|
|38|条件分发 / data-condition|index-C5emChb3.js / SPARK|顺序条件分支/下游端口与首命中原生结果已验；schema保守标记。|参考True/False双路三步骤与A/B独立输出字段模型不同，不能套样式冒充等价。|当前首命中分支实测；True/False独立schema需契约适配|REFERENCE_STATIC,LOCAL_SOURCE,LIVE_NATIVE_SQL_SUBSET|
|39|子流程 / sub-pipeline|subprocess-k7yb4-Py.js / SPARK|名称搜索/分页、固定发布版本、具名端口和纯转换子流协议。|参考仅在输入前/输出后接流程的模型不同；集成DS编译尚不展开版本化子流。|当前集成DS编译不展开子流程；复刻需明确执行边界|REFERENCE_STATIC,LOCAL_SOURCE|
|40|表输出组件 / insert-db|tableForm-CDTsGyqV.js / SPARK,FLINK,DATAX|双列独立拖拽/全选/勾选、独立状态保存恢复、缺失映射保留与显式校验、只读和节点切换保护；连接/表字段搜索、真实字段契约；10项交互回归通过。|pre/postSQL、更新主键与写入重试仍不支持；原生执行只证明已授权INSERT子集，不将映射界面单测当作全部写入模式的执行证据。|仅INSERT可执行；重试/更新/SQL hooks需真实DS适配|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_MAPPING_INTERACTION_UNIT_TEST_10,LIVE_PLATFORM_DS_JDBC|
|41|ODPS输出组件 / write-odps|odpsForm-BcOudzqq.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。表文本/模式/批次/pre-post SQL。|目标表/字段自动发现及双列拖拽全选映射仍缺，ODPS安全凭据引用执行未支持。|安全凭据引用适配未支持；hooks也不能假执行|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|42|Hive输出组件 / write-hive|hiveForm-CW0heqgv.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/表文本/模式/批次/hooks。|Hive目录字段读取、双列映射与真实执行绑定未验。|Hive执行绑定未验收；UI字段辅助可独立补|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|43|HDFS输出组件 / write-hdfs|hdfsForm-Dzow3PU9.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。目录/编码/类型/压缩/参数/三模式上游映射。|目标字段双列映射与外部HDFS真实写入未验；仅无凭据显式授权DataX编译。|DataX受管无凭据编译已有；外部HDFS未实测|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|44|MongoDB输出组件 / write-mongodb|mongoDBForm-BnYJNl4i.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。集合文本/where/模式/批次/主键配置。|集合目录自动发现、双列映射未补；DataX仅匿名受管集合INSERT，更新/SQL不支持。|DataX仅匿名集合全量/INSERT；更新/SQL未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|45|Redis输出组件 / write-redis|Redis-DwNFkGv3.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/类型/Key/批次配置。|参考只读上游属性勾选、按数据类型专用参数联动仍不同；Redis执行适配未支持。|Redis原生执行适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|46|RabbitMQ输出组件 / write-rabbitmq|RabbitMQForm-eXdThvhv.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/topic/批次条数1000，格式与凭据即时JSON反馈。|参考只读上游属性多选/类型呈现仍不同；RabbitMQ运行插件未支持。|RabbitMQ原生执行适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|47|kafka输出组件 / write-kafka|kafka-D4dKr8re.js / SPARK|专用平台连接引用/topic/配置JSON、上游属性与字节batchSize（默认10000，保持源参数键）。|Kafka真正受管执行未支持；格式和声明字段通过不等同消息投递成功。|Kafka原生受管执行尚未适配；不能仅改成配置JSON|REFERENCE_STATIC,LOCAL_SOURCE|
|48|RocketMQ输出组件 / write-rocketmq|RocketMQ-Bw-SIuIh.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。连接/topic/批次条数1000与即时JSON反馈。|参考只读上游属性多选/类型仍不同；源此项batchSize是条数（字节仅Kafka），RocketMQ执行未支持。|RocketMQ执行适配未支持|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|
|49|FTP输出组件 / write-ftp|FTP-DpedPV9Q.js / SPARK|字段增删、最后一行保护、确认删除、拖拽/Alt上下移、长度/小数精度/格式与隔离草稿属性编辑、上游获取三模式；JSON即时格式/凭据反馈。路径/编码/类型/压缩/写入模式/配置参数。|双列拖拽全选映射未补；FTP插件内联凭据要求尚无安全替代，执行拒绝。|FTP秘密引用适配未支持；原生插件需内联凭据|REFERENCE_STATIC,LOCAL_SOURCE,LOCAL_INTERACTION_UNIT_TEST|

完整字段、源SHA256、REA证据ID与最新本地文件/共享组件SHA256见同目录JSON。源bundle原件在本机`/tmp/qdata-rea-analysis`与补充目录，REA原始证据在`/tmp/qdata-component-audit-rea-evidence.json`，未将大型原始源码包提交。

CSV/Excel/JSONL文件辅助接口真实八项断言见`file-schema-api.json`；CSV管理员受管资源的DataX执行证据另见`csv.json`，两种授权路径不能混为一谈。

Kafka输出“单次写入字节”沿源键`taskParams.batchSize`，默认10000字节；RabbitMQ/Redis/RocketMQ的同名键是默认1000条。不能全局重命名为batchBytes，也不能交换单位。

字段预览的来源、length/precision与source归属语义见`qdata-field-preview-audit.md`；普通DB映射未发现字段类型兼容禁选，仅详情态禁用，HDFS目标勾选另行禁用。
