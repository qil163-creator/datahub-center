# 文件组件验证记录

## 范围与独立性

`yudao-spring-boot-starter-biz-file` 参考既有 infra 的存储能力，提供独立的 `core.client` 和 `core.remote` 接口；本轮没有迁移或修改 infra。实际业务消费方为 DataForge，数据库存储只保留通用 provider SPI，没有 infra 适配或业务依赖。旧模块目录、artifact、旧 Java 包及静态工厂调用已清除，ETL starter 未修改。

## 组件测试

2026-10-16 使用 Corretto 25.0.3，组件完整 `clean test` 退出码为 0：45 项中 44 项通过，1 项因 Windows 进程无符号链接创建权限而跳过。随后增加旧 infra bean 名共存用例，执行：

```powershell
mvn -pl :yudao-spring-boot-starter-biz-file -am '-Dtest=YudaoFileAutoConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q
```

退出码为 0，8 项装配用例全部通过。最终 Surefire 报告合计 **46 项，45 项通过、0 失败、0 错误、1 跳过**；完整组件复现命令见 [README](README.md)，覆盖下列 11 个测试类：

| 测试类 | 数量 | 证据边界 |
| --- | ---: | --- |
| `YudaoFileAutoConfigurationTest` | 8 | 标准 imports 发现、默认/显式开启、关闭、两类工厂覆盖、provider/URL resolver 注入、容器关闭释放资源，以及模拟既有 infra 配置和工厂 bean 名的共存 |
| `FileClientLifecycleTest` | 1 | 正在进行的操作期间替换/移除配置，稳定句柄、等待完成和旧客户端释放 |
| `FileConfigJsonContractTest` | 2 | Jackson 3 配置往返、密码字段不进入 toString、拒绝开放类型提示 |
| `FtpStorageTest` | 2 | 真实 Apache FTPServer 服务，主动/被动模式及字面百分号文件名 |
| `LocalStorageTest` | 6 | 本地 CRUD、路径越界、32 MiB 读取上限、真实 Windows Junction 与未创建根目录的祖先重定向；1 项符号链接创建权限不足而跳过 |
| `S3FileClientTest` | 5 | S3 URL 编解码、预签名和服务商端点选择 |
| `S3StorageProtocolTest` | 1 | 本机 HTTP S3 协议 fixture 的上传、读取和删除 |
| `SftpStorageTest` | 2 | 隔离的真实 Apache SSHD SFTP 服务，可信主机指纹、错误指纹拒绝、路径和传输 |
| `FtpRemoteFileClientTest` | 9 | 真实 Apache FTPServer，流式 CRUD、覆盖补偿、备份保留、受限遍历/ZIP、主动模式和字面百分号 |
| `RemoteFileSafetyTest` | 4 | 配置、路径、编码歧义、大小边界、硬超时和调用方流所有权 |
| `WebHdfsRemoteFileClientTest` | 6 | 本机 HTTP NameNode/DataNode 协议 fixture，重定向约束、符号链接拒绝、失败补偿、Jackson 3 往返、注入工厂、字面百分号只编码一次 |

符号链接用例的实际跳过原因为 `当前操作系统不允许测试进程创建符号链接`；没有把该用例报告为通过。Windows Junction 用例实际执行并通过。SFTP fixture 调整关闭方式后没有异步关闭异常。

WebHDFS **没有使用真实 Hadoop 集群**，不证明 Kerberos 或特定集群发行版兼容性；目前只支持显式 WebHDFS 端点和简单认证。S3 **只验证了 HTTP 协议 fixture，没有真实 MinIO/S3 云端验证**；当前 Docker 服务不可用且没有可用 MinIO 端点，不能据此宣称各云厂商完整兼容。

## 版本与依赖核对

实际执行的命令均退出 0：

```powershell
mvn -o -pl :yudao-spring-boot-starter-biz-file org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom '-Doutput=target/biz-file-effective-pom.xml' -q
mvn -o -pl :yudao-spring-boot-starter-biz-file -am org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree '-Dverbose' '-DoutputFile=target/biz-file-dependencies.txt' -q
```

| 项目 | 核对结果 |
| --- | --- |
| Java / Maven compiler source / target | 25 |
| Spring Boot / 配置注解处理器 | 4.1.1 |
| Spring Framework | 7.0.9 |
| Jackson Databind / Core | `tools.jackson` 3.1.5 |
| Jackson annotations | 共享注解包 2.21，不是 Jackson 2 Databind |
| Commons Net | 3.13.0 |
| JSch | 2.28.7 |
| AWS S3 SDK | 2.55.5 |
| Apache SSHD | 2.15.0，仅测试作用域 |

详细依赖树未出现 `omitted for conflict`，没有 Jackson 2 Databind/Core 或旧 `javax` 依赖。AWS SDK 的 `software.amazon.awssdk:third-party-jackson-core` 是其独立封装坐标，没有作为平台 Jackson mapper 注册。生产代码使用 Jackson 3；没有 Hadoop SDK、DB Switch、ETL 或业务 server 依赖。`spring-web` 显式满足公共 URL 编码工具的运行依赖，S3/JSch 是独立存储实现的协议依赖。

产物 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 包含 `cn.iocoder.yudao.framework.file.config.YudaoFileAutoConfiguration`。生成配置元数据包含 `yudao.file.enabled`，默认 `true`。存储工厂默认 bean 名为 `fileStorageClientFactory`，避开既有 infra 的 `fileClientFactory`；最小上下文已验证两套名称共存，没有引入 infra-server 测试依赖。

## 直接消费方与仓库门禁

主任务最终验证：DataForge 72 项回归全部通过；后端 `python tools/quality/run_all.py` 与前端质量门禁均退出 0。主任务也确认 `git diff -- yudao-module-infra` 为空，并清理 infra 构建产物，避免之前验证中的实验类残留。
