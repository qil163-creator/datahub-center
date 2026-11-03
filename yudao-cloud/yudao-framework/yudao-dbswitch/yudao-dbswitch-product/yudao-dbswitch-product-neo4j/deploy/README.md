# Neo4j 连接与独立开发实例

项目连接 Neo4j 时需要 Maven 中的官方 `neo4j-java-driver` 和一个已经运行的 Neo4j 服务。服务可以是已有实例、云服务，也可以使用本目录的 Compose 新建。连接组件不需要复制服务的数据库目录。

qData 参考包的 `安装包/docker/neo4j` 是运行目录，包含 `conf`、`data`、`logs`，其中 `data/dbms` 含认证状态，`data/databases`、`data/transactions` 含数据库和事务日志。其 Compose 使用 Neo4j 4.4.40；部署 PDF 将 Neo4j 列为独立依赖镜像。可以参考启动方式与参数，不应把历史账号、数据文件、锁文件和日志提交到本项目。

本目录的样例使用独立命名卷，默认 Neo4j 5.26 Community。需要验证 qData 参考版本时，可通过 `NEO4J_IMAGE=neo4j:4.4.40-community` 启动全新的兼容性实例。**不能把现有 4.4 数据卷直接切换到 5.x 镜像**；版本升级应按厂商升级流程操作，验证不同版本时使用不同 `-p` 项目名。

## 启动

在本目录打开 PowerShell 7，通过交互输入设置密码（不要提交密码或含密码的 `.env`）：

```powershell
$env:DATAHUB_NEO4J_PASSWORD = Read-Host '输入独立开发实例的密码（至少 8 字符）' -MaskInput
docker compose up -d --wait --wait-timeout 180
```

验证 4.4.40 时使用独立项目名：

```powershell
$env:NEO4J_IMAGE = 'neo4j:4.4.40-community'
docker compose -p datahub-neo4j-44 up -d --wait --wait-timeout 180
```

两套实例不能同时占用默认端口；需要同时运行时设置另一组 `NEO4J_BOLT_PORT`、`NEO4J_HTTP_PORT`。Docker Desktop 使用 Linux 容器。健康检查实际认证并执行 `RETURN 1`，仅启动进程不代表认证已通过。

## 在数据连接中填写

| 字段 | 此样例的配置 |
| --- | --- |
| 连接类型 | Neo4j |
| 连接地址 | `bolt://127.0.0.1:27687` |
| 用户名 | `neo4j` |
| 密码 | 启动时交互输入的独立密码 |
| 数据库 | `neo4j` |

浏览器管理页面是 `http://127.0.0.1:27474`；**7474/HTTP 管理地址不能作为 Bolt 数据连接地址**。测试连接会验证 Bolt、认证、指定数据库，再执行常量返回；不读写图节点。

这里的回环地址适用于 Java 服务运行在宿主机。如果 Java 服务也在 Docker 内，应加入同一网络并使用容器服务名和容器端口 `bolt://neo4j:7687`，不能使用该容器自身的 `127.0.0.1`。

当前支持 `bolt://`、`neo4j://`、`bolt+s://`、`neo4j+s://`。`bolt` 直连适合此端口映射样例；`neo4j` 会读取服务端路由表，集群/容器环境必须确保路由表中宣告的每个地址都能从 Java 服务访问。数据库必须在单独字段填写，地址中不能嵌入路径、账号、密码或查询参数。

`+s` 启用证书和主机名验证，适用于 Aura 或可信 CA 证书实例；本样例仅用于回环地址上的明文 Bolt。内部 CA 需要由运维加入 JVM 信任库。当前不接受 `+ssc` 跳过证书校验，也不会在 TLS 失败后回退明文。默认库 `neo4j` 是表单初值，若实例修改过默认库应填写实际可访问的数据库。

## 停止与数据

```powershell
docker compose stop
```

`stop` 和 `down` 均保留命名卷。使用 `-p datahub-neo4j-44` 启动时，后续命令也要带上相同项目名。删除数据卷会永久删除该实例的数据，正常停止无需删除卷。初始化密码只在空数据卷首次启动时生效，已有实例修改密码应使用 Neo4j 的账号管理功能，不能仅修改环境变量。

## 兼容性与验证依据

- 当前驱动版本来自项目 BOM，不从 qData 镜像或插件目录复制 JAR。官方 Java Driver 6.x 支持 Neo4j 4.4.x、5.x 及后续版本：[官方兼容说明](https://neo4j.com/docs/java-manual/current/install/)。
- 协议、路由、TLS 和 URI 约束：[官方连接说明](https://neo4j.com/docs/java-manual/current/connect-advanced/)。
- `Neo4jConnectionProbeTest` 验证配置拒绝、资源关闭和错误分类；`Neo4jNativeConnectionTest` 通过显式本机地址及环境密码启用，覆盖真实认证、错误密码、错误库、TLS 拒绝和无响应对端超时。无真实实例时会明确跳过，不能据此声称实机通过。
- 2026-10-19 已对官方 Neo4j 4.4.40 Community 和 5.26.32 Community 分别实际执行上述 5 项网络测试，均为 5 项通过、0 跳过；4.4.40 另通过 DB Switch 运行时的 3 项成功/错误凭据/错误数据库测试。5.26.32 使用本 Compose 文件启动并通过认证健康检查。TLS 正常连接需要实际受信任证书环境，集群路由还需要可达的成员地址，本次未以单机测试替代这两项验证。
- 本产品只声明 `CONNECT`，元数据、DDL、业务读写和同步均保持 `UNSUPPORTED`。
