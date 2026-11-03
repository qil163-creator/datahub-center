# 文件 Spring Boot Starter

`yudao-spring-boot-starter-biz-file` 提供文件存储客户端，以及数据源内的远程目录浏览、受限流式传输和文件操作。它不保存业务元数据、租户或权限，业务调用方负责先校验对象授权、从已授权连接记录组装配置、记录操作结果。

能力归属检索：现有 `infra-api FileApi` 是平台文件存储的 `byte[]` 上传/下载契约；既有 `infra-server` 的 FTP `FileClient` 没有目录浏览、路径隔离、重命名和流式下载公开接口，且跨业务不得依赖 `infra-server`。DB Switch 处理数据库协议，不承担文件系统。按用户要求保留 infra 实现与使用方式，参考其本地/FTP/SFTP/S3 能力和配置设计，提供这个独立 starter。`core.client` 是组件自己的通用存储接口，FTP 存储客户端复用本组件的远程协议工厂；DataForge 实际消费 `core.remote` 进行文件目录管理。

## 两类接口

| 接口 | 用途 | 资源所有权 |
| --- | --- | --- |
| `core.client.FileClientFactory` / `FileClient` | 本地、FTP、SFTP、S3 文件存储；`byte[]` 上传、32 MiB 以内读取与 S3 预签名，上传大小由调用方前置限制 | 工厂按配置编号管理客户端，替换/移除/容器关闭时释放资源 |
| `core.remote.RemoteFileClientFactory` / `RemoteFileClient` | FTP/WebHDFS 目录列表、统计遍历、流式传输、重命名、目录操作及 ZIP | 工厂无状态，每次 `open` 创建会话，由调用方 `try-with-resources` 关闭 |

`core.client.FileClientProvider` 是自定义存储扩展点；当前 starter 不实现数据库存储，也没有 infra 的 provider 适配。`core.client.FileUrlResolver` 默认按域名和编码后路径拼接 URL，使用方可注册自定义 bean。组件不依赖 infra 的 Mapper、DO、权限码或业务 URL 模板，既有 infra 不依赖或接入此 starter。

`YudaoFileAutoConfiguration` 分别通过 `@ConditionalOnMissingBean` 注册两类工厂及默认 URL resolver，`yudao.file.enabled=false` 同时关闭这些默认 bean。显式自定义的 bean 不受删除。DataForge 通过可选工厂启动；关闭时应用可正常启动，远程文件操作返回功能停用错误。此开关不控制既有 infra 文件功能。

存储接口的 `getContent` 限制为 32 MiB，较大文件应使用预签名或 `core.remote` 流式接口。存储配置按值复制，工厂提供稳定客户端句柄；配置替换和关闭等待当前句柄上的操作完成，业务缓存不会继续持有已经关闭的旧实现。本地文件每次检查已有祖先的真实路径，拒绝符号链接和 Windows Junction 重定向；SFTP 按操作创建会话并要求可信的 OpenSSH SHA-256 服务器公钥指纹，不自动信任未知主机。S3 调用总超时 2 分钟、单次尝试 30 秒。

## 使用与边界

- 公共 API 位于 `cn.iocoder.yudao.framework.file.core.remote`，自动配置位于 `cn.iocoder.yudao.framework.file.config`。使用平台 BOM 引入 `cn.iocoder.cloud:yudao-spring-boot-starter-biz-file`，通过构造器注入 `RemoteFileClientFactory`，调用实例方法 `factory.open(config)`，并用 `try-with-resources` 关闭会话。
- `YudaoFileAutoConfiguration` 由标准 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 发现。`yudao.file.enabled` 默认 `true`，只注册无状态工厂，不在应用启动时打开连接。设置为 `false` 不注册默认工厂；需要可选功能的消费方用 `ObjectProvider<RemoteFileClientFactory>` 检测是否启用，并返回自身领域的停用提示。调用方声明自己的工厂 bean 可覆盖默认实现，关闭自动配置不会移除该自定义 bean。
- `FileProperties` 仅保存组件启停配置，主机、账号、根路径和 WebHDFS 参数通过不可变 `RemoteFileConfig` 逐次传入。默认工厂可由多个请求共享；客户端只供一个请求串行使用，不共享连接、不缓存凭据、不读取租户或 Spring 安全上下文。
- `RemoteFileEntry.path` 总是相对于配置 `rootPath` 的虚拟绝对路径；例如配置 `/data/team` 时，接口 `/a.txt` 指向 `/data/team/a.txt`。
- FTP 默认使用被动模式，`RemoteFileConfig.ftpPassive=false` 可显式使用主动模式；两种模式均使用 UTF-8、二进制传输和 MLSD。服务器不支持 MLSD 时明确失败。被动数据连接只使用控制连接对端地址，不信任 PASV 返回的其他主机；主动模式验证数据连接对端。
- HDFS 使用显式 `webHdfsUrl` 的 WebHDFS 简单认证。此版本不支持 HDFS RPC、Kerberos 或密码认证；不会把配置中的 FTP 密码写入请求。DataNode 只能是 NameNode 原始 authority 或 `allowedDataNodeAuthorities` 中的精确 `host:port`，禁止协议变化、路径变化、操作变化、认证参数变化与第二跳重定向。生产环境需要使用 HTTPS 和可信端点。
- 拒绝 `..`、`.`、重复分隔符、反斜线、`%HH` 百分号编码串、控制字符和非法名称；允许 `100%+中文.txt` 这样的字面百分号名称，根路径与子项名称采用相同规则，WebHDFS 仅在生成 URI 时编码一次。每一步访问校验父目录。已声明的符号链接和特殊文件不能访问，列表不展示符号链接。根目录不可上传、重命名或删除。
- 协议客户端只能验证远端返回的元数据，无法识别 FTP 服务器隐瞒的符号链接，也无法阻止外部进程在校验后替换路径。严格文件系统隔离还必须由远端账号的根目录限制及 ACL 保证，不能仅依赖客户端配置路径。
- 默认连接超时 10 秒、读取超时 30 秒、整个会话硬上限 2 分钟；超时主动断开连接。单次传输不超过 500 MiB，单目录不超过 10,000 项、目录响应不超过 4 MiB。遍历最多 100,000 项/64 层/60 秒，业务应按场景传入更小上限。超限抛出异常，不返回不完整统计。
- 输入/输出流由调用方拥有，组件仅关闭自己的远程流。ZIP 在写出第一个字节前预检选中项、去重和总大小，并在读取期间再次按实际字节计量；文件变化或网络失败仍可能中止已经开始的响应，调用方不能把这种情况报告为下载完成。
- 删除目录采用先预检后逐节点删除，不具有跨文件事务。失败可能部分完成，调用方应按实际对象报告结果并允许刷新。

## 上传、覆盖和失败恢复

默认禁止重名上传。显式 `overwrite=true` 仍不能覆盖目录。上传先写当前请求的随机 `.remote-file-upload-*` 文件并验证实际大小；需要覆盖时把原件重命名为 `.remote-file-backup-*`，再提交暂存文件。提交失败会尝试把备份恢复到原名称，恢复失败保留备份并报错；成功提交但备份清理失败同样报错并保留备份。

FTP 没有原子的“仅目标不存在时重命名”或版本 CAS。客户端会在提交前再验证，但外部并发写入仍有竞争窗口；WebHDFS 的目录重命名也不是跨多步事务。平台应串行化同一连接/目标的写入，并让远端访问账号避免其他写入主体。断线、进程崩溃或强制超时可能留下当前请求的暂存文件或备份；这些名称可用于人工核对，组件不会自动删除无法确认归属或恢复状态的备份。失败后先刷新核对，不能无条件重试覆盖。

## 依赖与验证

生产依赖为项目 common、Spring Boot/自动配置/校验、Spring Context/Web、Apache Commons Net、JSch、AWS S3 SDK 和 Jackson 3，版本继承平台 BOM；不引入 Hadoop SDK、DB Switch、ETL 或业务模块。S3 SDK 和 JSch 分别支持独立组件的 S3/SFTP 能力，`spring-web` 显式满足公共 URL 编码工具的运行依赖。配置处理器是可选构建依赖；Apache FTPServer、Apache SSHD 和 Boot 测试库仅存在于测试作用域。组件无数据库事务、权限码或领域依赖。远程协议失败使用不含凭据和远端响应体的 `IOException`，非法输入使用 `IllegalArgumentException`；业务层转换错误语义并负责脱敏审计，组件不记录连接配置。

测试保留原 16 项协议与安全用例，并新增真实 FTP 主动模式传输：隔离的真实 Apache FTPServer TCP 服务覆盖中文路径、浏览、上传/下载、覆盖、重命名、受限 ZIP/递归删除、失败补偿与备份保留。WebHDFS 使用真实本机 HTTP 服务模拟 NameNode/DataNode 协议，覆盖重定向约束、符号链接、流式 CRUD 与提交恢复，**不等于真实 Hadoop 集群验证**。另有最小 Spring 上下文、Jackson 3 record 往返、路径与硬超时测试。自动装配测试验证 imports 发现、默认/显式开启、关闭、自定义工厂及 URL resolver 覆盖、存储 provider 注入和容器关闭释放资源，并验证启动不连接远端。

独立存储接口另有本地文件系统、真实 FTP/SFTP 服务和 S3 HTTP 协议 fixture 测试，以及并发操作期间替换/移除配置的生命周期测试。S3 fixture 验证本组件的请求和响应行为，**不等于真实 S3/MinIO 或各云厂商兼容性验证**；平台不具备符号链接创建权限时，相关测试会明确跳过并在记录中列出。

完整组件验证命令（含存储、JSON 和生命周期用例）：

```powershell
mvn -pl :yudao-spring-boot-starter-biz-file -am '-Dtest=FtpRemoteFileClientTest,WebHdfsRemoteFileClientTest,RemoteFileSafetyTest,YudaoFileAutoConfigurationTest,FileClientLifecycleTest,FileConfigJsonContractTest,FtpStorageTest,LocalStorageTest,S3FileClientTest,S3StorageProtocolTest,SftpStorageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

需要使用根 POM 对应的 JDK 25；依赖版本及测试执行结果见 [验证记录](EVIDENCE.md)。

协议参考：[Apache Commons Net FTPClient](https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPClient.html)、[Apache Hadoop WebHDFS](https://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-hdfs/WebHDFS.html)。
