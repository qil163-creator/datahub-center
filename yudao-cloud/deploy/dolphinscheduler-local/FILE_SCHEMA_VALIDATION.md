# 集成配置页文件字段解析

`POST /admin-api/dataforge/integration/file-schema` 是只读配置辅助接口，不创建、启动或执行 DS 任务。

请求示例：

```json
{"fileId":"9007199254740993","format":"EXCEL","startRow":2,"startColumn":1,"sheetName":"数据"}
```

`fileId` 是普通 infra `/file/upload-with-info` 接口返回的文件编号。当前登录用户必须拥有集成新增或修改权限，且必须是文件原上传者；不接受 URL、磁盘路径、其他用户编号或批量入库受管文件编号。服务间读取继续使用既有 mTLS ManagedFileApi，校验证书失败时直接拒绝，无明文或任意 URL 回退。普通上传支持 `.jsonl/.ndjson` 文件名，并继续独立检测实际内容类型；伪造 JSON 内容类型不能使未知二进制通过。

Excel 支持 `.xls/.xlsx`。`startRow` 表示首数据行，1 基、默认 2；大于 1 时首行为标题行，等于 1 时不跳过首行、按原始列位置生成 `column_1` 等字段名并提示。`startColumn` 是首读取列，1 基、默认 1。qData 静态前端传参名是 `startData/startColumn/excelFile`，提示“2 表示跳过第一行标题”；未捕获 qData 后台解析代码，不能据此证明其内部实现与本接口完全一致。

JSONL 支持 UTF-8 `.jsonl/.ndjson`，每行是非空 JSON 对象。采样行字段集合必须一致，拒绝重复键、尾随非 JSON 文本、空行、非法 UTF-8。嵌套对象/数组、混合类型、全空列及 Excel 公式保持未知类型，不执行公式、不从字符串猜测日期或数字。

CSV 支持 UTF-8 `.csv`、逗号分隔、双引号转义及跨行字段，复用 `TabularFileReader` 的字节、行、列与单元格预算。CSV没有类型声明，按文本返回 `STRING`，不根据数值样式或日期样式猜测类型；重复表头、非法编码、字段数量不一致及超限输入会拒绝。解析不会把上传附件自动绑定到 DS 工作节点，已有受管路径也不能代替新附件执行。

响应仅有 `fields/warnings/partial`。`partial` 固定为 `true`，Excel、JSONL 类型来自最多 1000 行采样，CSV按文本字段处理；文件未声明数据库长度/精度，`length/precision/scale` 均为 `null`。不返回数据行。文件上限 20MB，CSV、Excel 上限 10000 行、256 列，JSONL 单行 65536 字符、嵌套 16 层。Excel 复用 ETL 既有 ZIP/OLE 扫描与解压、单元格和文本内存预算；存储读取调用预分配前有界 API，并核对实际字节数。

聚焦验证：

```sh
mvn -q -pl :yudao-module-infra-server,:yudao-module-dataforge-server -am -Dtest=FileSchemaReaderTest,TabularFileReaderTest,UserFileContentReadTest,ManagedFileRpcSecurityTest,IntegrationFileSchemaServiceTest,IntegrationFileSchemaControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 tools/quality/run_all.py
```

聚焦测试均通过，覆盖真实 CSV/XLS/XLSX 字节解析、JSONL 采样、首数据行/起始列、公式不求值、未知类型、重复字段/重复键、非法编码/结构/配额、跨用户及受管素材拒绝、限定存储 API、mTLS 拒绝、RBAC 和 HTTP 参数校验。CSV新增检查与现有文件接口及DataX误用保护共25项已复验，零失败、零跳过。

另外已运行跨进程验收，断言结果见 [file-schema-api.json](verification/file-schema-api.json)：真实上传→集成接口→双向TLS Infra RPC→隔离数据库和文件存储；校验 CSV文本字段/错误格式拒绝、JSONL 类型/未知字段、Excel 首行列/公式、无标题保留首行、跨上传者拒绝、缺少客户端证书拒绝、重复键拒绝。此功能是元数据辅助，不扩大当前 DS 引擎对文件组件的执行支持。

本次运行入口为 `http://localhost:6001` 临时前端代理、DataForge `127.0.0.1:50025`、Infra `https://localhost:48085`。原用户 IDE 服务和原 5999/48083 入口均保留。临时代理仅将 `/admin-api/infra/file*` 固定转到新 TLS Infra，并使用显式 CA、客户端证书与私钥；证书和主机名校验保持开启。Infra 的 TLS `client-auth=want` 允许普通上传继续使用登录/RBAC，受管文件 RPC 仍强制客户端证书及服务证书 SHA256 指纹校验。数据库仅使用隔离容器 `codex-integration-pg-api-test`，其中 config 29 的本地文件存储指向私有临时目录。

证书材料由 `prepare_file_schema_tls.py` 生成，有效七天，目录权限 0700、文件 0600，位于 `/private/tmp/datahub-file-schema-tls`。`infra.properties` 是新 Infra 的追加配置，`dataforge.properties` 必须在现有 DataForge 私有配置后加载；`paths.json` 仅供本机代理取得材料路径。不得提交整个临时目录。普通未配置该 RPC 证书的开发服务依然会拒绝读取；不要启用明文或忽略证书回退。

```sh
python3 deploy/dolphinscheduler-local/prepare_file_schema_tls.py --base-properties /tmp/datahub-integration-private.properties
# 新 Infra 实例只加载私有 infra.properties；既有 50025 由其所有者追加 dataforge.properties 后重启。
java -Xms128m -Xmx512m -jar /tmp/datahub-file-schema-infra.jar --spring.profiles.active=local --spring.config.additional-location=file:/private/tmp/datahub-file-schema-tls/infra.properties
python3 deploy/dolphinscheduler-local/file_schema_smoke.py
```

脚本会新增本次上传记录与唯一文件路径，不覆盖原文件。浏览器可使用同目录 `sample.xlsx` 与 `sample.jsonl` 复验。停止仅针对 `infra.pid` 记录且命令行为 `/tmp/datahub-file-schema-infra.jar` 的本次实例；不要停止用户原 Infra。

## 原 5999 / 50024 环境如何加载新接口

2026-11-01 只读检查时，原 `5999` 页面仍通过原网关访问 IDE 启动的 `50024`。该进程使用 `DataforgeServerApplication`、模块 `yudao-module-dataforge-server` 与 `target/classes`，未加载 Spring Boot DevTools；新 `/integration/schema` 和 `/integration/file-schema` 映射尚未加载。Actuator 提供的 `refresh` 只能刷新配置，不能注册这次新增的 Controller/方法，浏览器刷新和普通 HotSwap 也不能完成此次结构变更。

以下命令均从 **yudao-cloud 仓库根目录** 执行，使用仓库现有 Java 25 与 Maven：

```sh
mvn -q -pl :yudao-module-infra-server,:yudao-module-dataforge-server -am -DskipTests package
```

推荐在 IntelliJ 保留原 `DataforgeServerApplication` 运行配置、原数据库和已有 XXL 参数，然后手动 **Rerun**；这会更换 JVM 进程。原配置已有 `Before launch: Make`，但已运行的 JVM 不会因为 Maven 编译产物更新而自动注册新接口。本次验收没有重启或杀掉原 IDE 进程，也没有修改原 `5999/48083` 路由。已验证的新功能入口仍为 `6001`。

文件解析还需要与**原上传文件元数据所在数据库和存储**匹配的安全 RPC。具体配置边界如下：

- Infra 必须加载本次新增的 `readUserUpload` 实现，并提供独立、仅本机监听的 HTTPS 入口；配置服务端证书/信任库和 `yudao.rpc.peers.dataforge[0]` 的客户端证书 SHA256 指纹。即使 TLS 使用 `client-auth=want`，受管 RPC 过滤器仍强制真实证书和指纹认证。
- DataForge 配置 `yudao.rpc.client.ssl-bundle`，以及该 `spring.ssl.bundle.jks.<bundle>` 的客户端密钥库和 CA 信任库；`spring.cloud.openfeign.client.config.managedFileApi.url` 必须指向上述 HTTPS 入口。保持 CA 与主机名校验，不使用明文、信任全部证书或任意文件 URL 回退。
- 本次 `50025/48085` 使用隔离 PostgreSQL 与私有上传目录。不能将其 TLS overlay 直接套给原 `50024`，也不能把两个数据库的 `fileId` 混用。原环境的 TLS 实例应复用原文件数据库/存储配置；证书、口令与私有配置文件保存在仓库外。

如果选择用打包产物手动启动，下面是相对路径入口。`INFRA_TLS_PROPERTIES` 与 `DATAFORGE_TLS_PROPERTIES` 应指向仓库外、权限为 0600 的**原环境专用**配置文件。先停止对应旧 IDE 实例再替代启动 `50024`，不要与同端口旧进程并行；独立 Infra TLS 实例应在私有配置中选择空闲本机端口，并保留原数据库/文件存储，禁止把原网关切换到隔离验收库。

```sh
java -Xms128m -Xmx512m -jar yudao-module-infra/yudao-module-infra-server/target/yudao-module-infra-server.jar --spring.profiles.active=local --server.address=127.0.0.1 --spring.cloud.nacos.discovery.register-enabled=false --spring.config.additional-location="file:${INFRA_TLS_PROPERTIES}"
java -jar yudao-module-dataforge/yudao-module-dataforge-server/target/yudao-module-dataforge-server.jar --spring.profiles.active=local --server.port=50024 --spring.config.additional-location="file:${DATAFORGE_TLS_PROPERTIES}"
```

手动替代启动时仍须带上原运行配置要求的其他参数；上述示例不包含本机口令、私钥或证书内容。元数据接口的可用性与 DS 的项目、Worker、引擎环境绑定是不同配置项，不能用文件解析成功代替任务执行验收。
