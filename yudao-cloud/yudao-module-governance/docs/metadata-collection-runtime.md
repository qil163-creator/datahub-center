# 元数据采集运行契约

元数据任务、执行实例和已采集文件记录归 Governance。DataForge 拥有连接配置，通过 `MetadataCollectionApi` 提供安全展示选项、来源系统校验和只读文件 manifest；文件协议执行复用 `yudao-spring-boot-starter-biz-file`。新文件采集链路不传递密码，不使用或修改 infra 文件配置、文件记录及文件内容。关系库元数据读取继续复用已有 DB Switch 和数据库 RPC 解析链路。

## 执行与恢复

部署后在 XXL-Job 启用 `governance-server / metaCollectTaskJob`，建议每分钟执行一次。手动触发也会立即尝试消费队列；服务重启后的等待实例、失联租约恢复及周期排期由此任务扫描。周期时间推进与实例入队在同一 PostgreSQL 事务内完成。

实例状态为 `0 排队 / 1 运行 / 2 成功 / 3 失败 / 4 部分成功 / 5 已丢弃`。任务上下线与调度启停各自独立。平台最多同时运行两个实例，最多一百个等待实例，每节点工作线程有界。实例使用五分钟租约、每分钟续租；过期后重新全量扫描，累计三次失联转失败并保留记录，须修复执行环境后人工触发。业务修改期间禁止变更仍有活动实例的任务配置，避免等待任务执行到后来修改的范围。

策略含义：

- `PARALLEL`：同一任务可并行，仍服从平台运行上限。
- `SERIAL_WAIT`：同一任务按持久实例编号先入先出。
- `SERIAL_DISCARD`：已有排队或运行实例时，本次保留“已丢弃”记录。
- `SERIAL_PRIORITY`：保留当前运行实例，将旧等待请求标为已丢弃，仅保留最新请求等待执行。这是本项目明确的语义，不中断正在执行的外部读取。

手动运行保存当前执行人，周期运行使用任务创建人。执行前及 DataForge 文件读取前重新检查执行人的采集查询/运行权限，撤权后不会继续访问源连接。未配置有效创建人的历史任务应由有权用户重新创建或手动执行。

## 文件与范围

文件路径是保存连接根目录下的逻辑路径，不是服务器绝对路径。文件采集仅执行目录状态与列表读取，最多 10000 个目录项、32 层、组件扫描截止时间 60 秒；还受每次协议读取超时限制。超限或任一枚举失败时不接受不完整 manifest，不执行缺失标记。单连接最多纳管 10000 条文件记录，目录树以已采集文件父目录构建，不显示未包含文件的空目录。

只有当前成功扫描路径下原来存在而本次缺失的文件标为待确认缺失。技术刷新保留平台业务名称、描述、负责人和业务修订号；源端不再提供修改时间时清空旧时间。文件业务属性通过 `bizRevision` 做 CAS 更新；文件删除只逻辑删除元数据，下一次成功采集仍存在的源文件会重新纳管。没有远端重命名、上传或删除操作。

文件任务的 `fileCount` 表示扫描文件数，`tableAdd/tableUpdate/tableDelete` 分别表示文件新增、物理变更、源端缺失数。统计与元数据在同一事务内提交；租约恢复重扫保留本实例已提交的累计变化。重复相同清单、仅维护业务属性以及重复缺失标记不增加变化计数。

文件筛选类型不区分大小写；目录节点筛选直接父路径，连接节点不附带路径条件并显示全部嵌套文件。未关联来源系统通过 `sourceSystemMissing=true` 筛选，不与所有来源系统混同。目录树最多处理平台内 10000 条纳管文件；超出时明确拒绝构造不完整树。

数据库自定义范围要求非空、有效 JSON、明确库名和表数组；空表数组表示整个指定库。保存时通过 DB Switch 实时核对库表身份；执行时保持精确标识符，不将字符串拼接成 SQL。无效范围不会退化为整个数据源。库表黑名单排除与源端删除分别处理。

## 服务边界与部署前提

`/rpc-api/dataforge/metadata-collection/**` 只接受经双向 TLS 认证的 Governance 服务。狭窄门禁在身份上下文过滤器前运行，只使用 Servlet 容器真实握手取得的客户端证书，对照 `governance` 的 SHA-256 叶证书指纹；不信任 `login-user`、`X-Forwarded-Client-Cert` 或 `actorId` 作为服务身份证明。Controller 再次检查门禁结果，漏注册过滤器时也拒绝调用。认证通过后仍查询执行人的业务权限，不能通过合法服务证书绕过撤权。

Feign 仅对此客户端启用 SSL bundle、服务端证书和主机名校验，拒绝明文 HTTP、缺失 bundle 和未知服务证书，连接超时 5 秒、读取超时 90 秒，不自动重试或跟随重定向。未配置证书不阻止整个服务启动，但元数据源选项及文件采集 RPC 会失败关闭；需要完成下面两端配置后使用。

Governance 的运行配置（证书和密码由部署环境提供，不写入仓库）：

```yaml
spring:
  ssl:
    bundle:
      jks:
        metadata-collection-client:
          keystore:
            location: ${GOVERNANCE_RPC_KEYSTORE_LOCATION}
            password: ${GOVERNANCE_RPC_KEYSTORE_PASSWORD}
            type: PKCS12
          truststore:
            location: ${DATAFORGE_RPC_TRUSTSTORE_LOCATION}
            password: ${DATAFORGE_RPC_TRUSTSTORE_PASSWORD}
            type: PKCS12
yudao:
  dataforge:
    metadata-collection:
      client:
        url: ${METADATA_COLLECTION_DATAFORGE_HTTPS_URL}
        ssl-bundle: metadata-collection-client
```

`METADATA_COLLECTION_DATAFORGE_HTTPS_URL` 应为内部 DataForge HTTPS 地址，主机名必须匹配服务端证书 SAN。配置了此 URL 时不依赖服务发现的明文默认地址；若省略，发现实例必须支持 HTTPS，最终实例地址仍由客户端检查，不能降级为 HTTP。

DataForge 的运行配置：

```yaml
server:
  ssl:
    enabled: true
    bundle: metadata-collection-server
    client-auth: WANT
spring:
  ssl:
    bundle:
      jks:
        metadata-collection-server:
          keystore:
            location: ${DATAFORGE_RPC_KEYSTORE_LOCATION}
            password: ${DATAFORGE_RPC_KEYSTORE_PASSWORD}
            type: PKCS12
          truststore:
            location: ${GOVERNANCE_RPC_TRUSTSTORE_LOCATION}
            password: ${GOVERNANCE_RPC_TRUSTSTORE_PASSWORD}
            type: PKCS12
yudao:
  dataforge:
    metadata-collection:
      rpc:
        peers:
          governance:
            - ${GOVERNANCE_CLIENT_CERT_SHA256}
```

`WANT` 允许同端口的其他 HTTPS 入口不出示客户端证书；本 RPC 门禁仍强制要求已认证且指纹匹配的客户端证书。服务端 truststore 必须信任治理客户端证书链，客户端 truststore 必须信任 DataForge 服务端证书链。指纹是叶证书 DER 的 SHA-256 十六进制值，不含冒号；轮换时可暂时同时配置新旧两个指纹。若在原服务端口启用 TLS，网关及其他访问该端口的调用方也必须切换到匹配的 HTTPS 地址和信任链；不能只修改此配置而继续向该端口发明文请求。TLS 必须终止在 DataForge 的 Servlet 容器，不能用代理转发的证书 HTTP 头代替实际客户端证书。

外部网关不路由 RPC。仓库 Compose 为业务服务使用 backend 网络且只有 `expose`，宿主端口只发布网关；这不替代服务认证，裸 JVM 部署也必须限制业务服务端口的可达范围。DataForge、Governance 和 System 服务端口不得直接暴露公网。

接口不返回 DO、凭据或执行租约。管理员前端通过治理 Controller 调用，仍需各自 `meta-collect-task`、`meta-collect-instance` 和 `meta-file` 权限。当前沿用平台内具备对应权限者全可见的元数据范围，没有从浏览器传入部门编号推导授权。

日志查询最多返回 10000 条；日志下载按 500 条分批读取并限 16 MiB，达到上限显式附加截断提示。新增采集失败信息只保存类别与提示，不记录外部异常中的连接串、账号密码或文件内容。

`MetadataCollectionRpcSecurityTest` 验证直连无证书、伪造身份头、缺失门禁、注册顺序和精确 MVC 路由；`MetadataCollectionTlsChannelTest` 使用当前 JDK 临时生成测试证书，在本机嵌入式 Tomcat 上验证真实 TLS 握手、合法服务和执行人、无证书/未允许证书拒绝、服务端证书及主机名校验。`MetadataCollectionProviderTest` 在认证请求下继续验证只读文件协议与业务授权。测试证书不用于部署，也不会写入仓库。
