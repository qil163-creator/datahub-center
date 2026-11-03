# 数据开发原生 Flink / Spark 执行

本实现适配现有 DataForge、Vben Antdv Next 和 DolphinScheduler 执行链路。参考 qData 的任务配置和页面交互，技术协议以 Apache DolphinScheduler **3.4.2** 的正式插件实现为准。任务保存、定时触发、幂等运行、lease、取消意图、UNKNOWN 对账及日志仍由既有数据开发生命周期管理。

## 组件复用与执行边界

已检索现有 DolphinScheduler REST starter、DB Switch 和 DataForge 发布、运行调用方。原生 Flink/Spark 插件参数及资源协议归入现有 DolphinScheduler starter，DataForge 只负责受管运行环境、发布授权、资源映射和状态编排。实际提交由 DS Worker 的原生插件调用匹配版本的引擎客户端完成；独立 Flink 运行组件已退役，业务服务不内嵌另一套引擎运行时或提交器。

| 任务类型 | 实际执行节点 | 执行内容 |
| --- | --- | --- |
| FLINK_SQL | FLINK，programType=SQL | Flink SQL Client 执行编辑器中的脚本 |
| FLINK_JAR | FLINK，programType=JAVA | 受管 JAR 制品和入口类 |
| PYFLINK | FLINK，programType=PYTHON | 受管 Python 制品 |
| SPARK_SQL | SPARK，programType=SQL | Spark SQL 执行编辑器中的脚本 |
| SPARK_JAR | SPARK，programType=JAVA | 受管 JAR 制品和入口类 |
| PYSPARK | SPARK，programType=PYTHON | 受管 Python 制品 |

没有 programConfig 的历史 FLINK_SQL/SPARK_SQL 任务保留其原有 SQL 通道，页面显示兼容能力。新建原生任务必须选择正式运行环境，不能把 JDBC 数据源编号当作集群配置。

Python 节点使用 DolphinScheduler 管理的 `.py` 制品。工作台中选择的制品是运行内容，草稿 content 不是 Python 执行入口。SQL 节点直接使用版本快照中的脚本。制品和依赖必须来自所选运行环境允许的资源集合。

| 部署档案 | 当前允许的引擎版本及任务 | 完成证据 |
| --- | --- | --- |
| `YARN_ATTACHED` | Flink 1.18.x、Spark 3.5.x；上表六种形态，Spark SQL 仅批处理 | DS 终态及可信 ResourceManager 中全部匹配应用的终态 |
| `LOCAL` | Flink SQL 1.20.3，批处理或持续流；Spark SQL 3.5.6，仅批处理，管理员固定 `local[1]` | DS 终态及可信 Worker 观察器记录的真实启动、引擎结果和整个进程树退出 |

`LOCAL` 不开放 JAR、Python、任意 Spark master 或独立集群地址。它复用既有 DS FLINK/SPARK 原生插件和 starter 的本地参数入口，不将任务改成 Shell，也不改变 YARN 的观察与终态判定。原生任务不接受 JDBC 数据源绑定及 `executionConfig.localParams`；需要移除表单中遗留的绑定参数，再选择受管环境保存。

普通 SQL 的离线或实时页面归属保存在 `executionConfig.workloadMode`，历史未设置值归入离线。这个分类不改变 JDBC 单次执行方式，实时页面中的普通 SQL 仍须绑定真实数据连接并设置正数超时，不表示 CDC 或持续监听。明确设置的 `executionConfig.workloadMode` 优先决定所有任务的页面归属；未设置时沿用 `programConfig.workloadMode`，再缺省为离线。真正的原生运行方式仍只由 `programConfig.workloadMode` 决定，因此实时页面可以包含实际执行批处理的 Spark SQL。

属性配置中的 `executionConfig.tableLineage` 保存人工确认的 `upstream`、`downstream` 表关系；每项为受管 `databaseId`、可选 `schemaName` 和 `tableName`。上下游合计最多一百项，同一方向不能重复。保存时通过已有 DatabaseService 和 DB Switch 元数据通道核对启用的数据连接、真实数据表及所属 Schema，字段不会拼接进任务 SQL，也不授予外部数据读写权限。任务上线期间不得变更这些关系；保存和回滚继续受修订号及原有权限约束。任务执行依赖仍单独保存在 `dependencyTaskIds`。当前没有公开可靠的自动 SQL 血缘解析接口，界面不猜测或伪造表关系。

## 授权与持久状态

原生运行权限是 `dataforge:dev-task:execute-native`，与现有保存、运行、调度权限同时校验。后台发送前复查保存版本的执行身份，不借用调度发起人的身份；运行环境按受管项目、Worker 组和操作系统用户限定范围。DataHub 业务采用单租户，DS 的执行租户仍表示外部操作系统用户。原生程序拥有对应隔离环境的代码执行能力，数据源的表级 SQL 授权不能代替这项授权。原生日志和下载仍受此权限及运行范围控制。

保存快照冻结任务配置及运行环境身份。执行时不能将被更改的运行环境悄悄套入旧保存版本。未知投递结果进入原有 UNKNOWN 核查流程，不自动重复启动。停止操作先记录取消意图，再由原有对账流程调用调度器。DolphinScheduler 的工作流停止不等于引擎或后代进程已经终止。

YARN 档案必须配置可信 HTTPS ResourceManager 观察入口和有效的 Spring SSL bundle，以 `dh_native_<recordId>` 精确运行名称及操作系统用户查找全部应用，再核验队列和引擎终态。查询限制十秒、1 MiB 响应和 64 个应用，禁止跳转，不返回集群 diagnostics、日志或内部 URL。空结果、截断、身份不匹配、配置撤回或任一应用未终止都不能证明已停止。原生 SUCCESS、FAILED 和 STOPPED 都需要受管 YARN 应用终态证据；SUCCESS 额外要求所有匹配应用均为 SUCCEEDED。这个判断不会因为新增 LOCAL 模式而放宽。

LOCAL 档案必须配置可信 HTTPS Worker 观察入口及双向 TLS 客户端档案。观察器通过 `GET /v1/executions?name=dh_native_<recordId>&user=<受管操作系统用户>` 返回受信执行记录。每项必须精确匹配运行名称、用户及引擎，并已真实启动、已完成、整个进程树没有活动进程；只看到客户端父进程消失、DS 强制成功或查询不到进程都不构成证明。成功还要求 DS 为 SUCCESS，全部观察项为 SUCCEEDED 且退出码均为零。已请求取消、DS 为 FAILURE，且可信记录确认完整进程树已结束时，平台收敛为 STOPPED；未请求取消的失败保留 FAILED。DS STOP 同样必须有完整退出证据。

LOCAL 观察接口只接受管理员固定入口和 TLS 档案，限制十秒、1 MiB 响应及 64 项，禁止跳转，不允许浏览器指定 PID、观察地址或操作系统用户。观察器应保留引擎启动、结果和退出记录，绑定真实 Worker 身份并追踪进程树，不能用历史 `ps` 空结果或伪造记录代替。空结果、未观察到启动、观察器不可用、引擎身份不符、仍有活动后代或档案轮换都会保留 UNKNOWN 和执行槽位。缺少证据时需恢复观察，或由运维核实后使用现有人工核查入口。

流式任务可将 timeoutSeconds 设置为 0 长期运行；批任务必须有正数超时。关闭超时时同时向 DS 传入 `timeoutFlag=CLOSE` 与 `timeout=0`；DS 3.4.2 的监视器仅判断分钟数，不能只关闭标志却保留正数。正数超时向上取整为分钟。Flink 采用 attached、SQL 同步等待 DML 及退出时关闭应用的受管设置；Spark YARN 等待应用完成，Spark LOCAL 等待受管客户端和本地引擎退出。用户脚本不能覆盖这些生命周期设置。现代 Flink 的 TaskManager 配置通过总 slot 容量上限表达，并非承诺固定启动相同数量的 TaskManager。

新投递在缺少对应部署模式的可信观察配置或 SSL bundle 时被拒绝；历史已投递实例的观察配置失效时，无法自动收敛终态。纯元数据 SQL 或不产生 YARN 应用的程序不会自动判定为 YARN 原生成功；纯元数据操作可使用已有 SQL 通道。当前执行范围为上述受管 YARN 任务和 LOCAL SQL，不包含已验证的 Kubernetes、独立集群或任意本地脚本执行。

原生程序可以把任意输出包装为标准日志记录，正则规则无法可靠证明某行不是查询数据或参数。因此平台提供固定投递、状态及终态诊断；调度器原生日志分页和下载保留时间、级别并隐藏原始正文。原生编译器和程序的完整输出由受控集群运维渠道查看，不能把脱敏后的分页验收称为完整引擎日志验证。

新增迁移 `0147_dataforge_native-engine-execution.sql` 仅登记独立权限，不自动给任何角色或套餐赋权，不导入 qData 的用户、集群、连接凭据、脚本或演示数据。已有数据库在 0146 后执行 0147；新数据库使用包含本迁移的当前全量基线，文件首行标记随后续编号迁移继续提升。

## 部署前提

运行节点需要安装与运行环境版本匹配的 DolphinScheduler FLINK/SPARK 插件及 Flink/Spark 客户端，并配置专用 Worker 组和非 root 操作系统租户。平台服务配置只登记经过验证的运行环境和允许的制品；凭据通过环境或部署的密钥管理提供，不放入草稿、SQL、程序参数或版本快照。

本适配的原生命令面向 Linux/POSIX Worker。DS 插件原样拼接 Worker 的客户端安装和工作目录，部署路径必须没有空格或 shell 特殊字符；平台对制品引用、依赖、入口类与程序参数分别校验及编码。Worker 的引擎 JDK 必须独立配置，不能默认继承平台服务的 JDK 25；部署时按 [Flink 1.18 Java 兼容说明](https://nightlies.apache.org/flink/flink-docs-release-1.18/docs/deployment/java_compatibility/) 和 [Spark 3.5 Java 兼容说明](https://spark.apache.org/docs/3.5.8/) 校验相应客户端、用户制品和 Python 环境。

执行版本与模式以本文部署档案表为准。Spark SQL 是批任务，SQL Driver 位于受管 Worker；Spark JAR/Python 仍限 YARN cluster 模式。原生 Flink checkpoint 间隔映射到引擎配置。未就绪定义可以逐步保存为草稿，但只有符合受管档案的完整定义才能生成可运行版本。

配置前先在 DolphinScheduler 登记专用项目、租户、环境和 Worker 组，并确认 Worker 环境中的客户端及 Hadoop 配置。平台侧配置示意如下，变量来自部署环境；不得直接把示例粘贴为可用集群：

```yaml
dataforge:
  dev:
    native:
      profiles:
        - code: flink-production
          name: 受管 Flink 环境
          revision: deployment-1
          ds-project-code: ${DATAFORGE_NATIVE_DS_PROJECT_CODE}
          ds-version: 3.4.2
          engine: FLINK
          engine-version: 1.18.1
          deploy-mode: YARN_ATTACHED
          worker-group: ${DATAFORGE_NATIVE_WORKER_GROUP}
          tenant-code: ${DATAFORGE_NATIVE_OS_USER}
          environment-code: ${DATAFORGE_NATIVE_DS_ENVIRONMENT_CODE}
          queue: ${DATAFORGE_NATIVE_YARN_QUEUE}
          allowed-task-types: [FLINK_SQL, FLINK_JAR, PYFLINK]
          yarn-endpoint: ${DATAFORGE_NATIVE_YARN_ENDPOINT}
          yarn-ssl-bundle: yarn-native
          artifacts:
            - file-id: ${DATAFORGE_NATIVE_JAR_RESOURCE_ID}
              name: 已审核的作业制品
              sha256: ${DATAFORGE_NATIVE_JAR_SHA256}
              resource-name: ${DATAFORGE_NATIVE_JAR_RESOURCE_NAME}
              worker-relative-path: ${DATAFORGE_NATIVE_JAR_RELATIVE_PATH}
              max-bytes: 67108864
              program-types: [JAR]
              entry-point: ${DATAFORGE_NATIVE_JAR_ENTRY_POINT}
          dependency-resources: []
spring:
  ssl:
    bundle:
      pem:
        yarn-native:
          keystore:
            certificate: ${DATAFORGE_NATIVE_YARN_CLIENT_CERTIFICATE}
            private-key: ${DATAFORGE_NATIVE_YARN_CLIENT_KEY}
          truststore:
            certificate: ${DATAFORGE_NATIVE_YARN_CA_CERTIFICATE}
```

`yarn-endpoint` 是提供 Hadoop 3.3.6 ResourceManager REST 契约的 HTTPS 根地址或认证代理入口，允许代理路径前缀；要求校验证书和可信服务身份，不使用查询串 `user.name` 模拟认证。配置中的队列必须与 RM 返回的规范队列名称一致。ResourceManager 的 Kerberos/SPNEGO 环境需通过已经验证的受控认证代理提供此接口，当前客户端没有引入另一套 Hadoop 安全运行时。

LOCAL SQL 的档案示例使用已有 DS 专用 Worker 及引擎环境，变量由实际部署提供。观察器服务端必须校验客户端证书，客户端同时校验观察器证书；不要将测试私钥写进代码或版本库：

```yaml
dataforge:
  dev:
    native:
      profiles:
        - code: flink-local-sql
          name: 受管本地 Flink SQL
          revision: deployment-1
          ds-project-code: ${DATAFORGE_LOCAL_DS_PROJECT_CODE}
          ds-version: 3.4.2
          engine: FLINK
          engine-version: 1.20.3
          deploy-mode: LOCAL
          worker-group: ${DATAFORGE_LOCAL_WORKER_GROUP}
          tenant-code: ${DATAFORGE_LOCAL_OS_USER}
          environment-code: ${DATAFORGE_LOCAL_FLINK_ENVIRONMENT_CODE}
          queue: default
          allowed-task-types: [FLINK_SQL]
          local-observer-endpoint: ${DATAFORGE_LOCAL_OBSERVER_ENDPOINT}
          local-observer-ssl-bundle: local-native
          artifacts: []
          dependency-resources: []
        - code: spark-local-sql
          name: 受管本地 Spark SQL
          revision: deployment-1
          ds-project-code: ${DATAFORGE_LOCAL_DS_PROJECT_CODE}
          ds-version: 3.4.2
          engine: SPARK
          engine-version: 3.5.6
          deploy-mode: LOCAL
          worker-group: ${DATAFORGE_LOCAL_WORKER_GROUP}
          tenant-code: ${DATAFORGE_LOCAL_OS_USER}
          environment-code: ${DATAFORGE_LOCAL_SPARK_ENVIRONMENT_CODE}
          queue: default
          spark-master: local[1]
          allowed-task-types: [SPARK_SQL]
          local-observer-endpoint: ${DATAFORGE_LOCAL_OBSERVER_ENDPOINT}
          local-observer-ssl-bundle: local-native
          artifacts: []
          dependency-resources: []
spring:
  ssl:
    bundle:
      pem:
        local-native:
          keystore:
            certificate: ${DATAFORGE_LOCAL_CLIENT_CERTIFICATE}
            private-key: ${DATAFORGE_LOCAL_CLIENT_KEY}
          truststore:
            certificate: ${DATAFORGE_LOCAL_OBSERVER_CA_CERTIFICATE}
```

当前 LOCAL 小型验证环境仅验证了一核预算：Driver/Executor 核数、并行度、Slot、TaskManager 和 Executor 数均须为 1，未设置时按 1；每项内存只允许 512、768 或 1024 MB，未设置时按 1024 MB；Spark master 固定为 `local[1]`。保存完整版本前即校验这些预算，超限配置不能先发布再等 Worker 启动失败。扩容需增加经过验证的受管档案能力，不代表共享参数组件支持的更大范围已经在本环境验收。YARN 的既有资源范围不受此限制。

LOCAL 的 `queue: default` 是现有任务配置匹配与参数校验使用的固定值，实际提交的本地参数不包含 YARN 队列。`runtimeProfileCode`、`engineVersion`、`queue` 必须与选定档案一致。`spark-master` 及观察地址只在部署档案中设置，不从任务表单接收。

手动运行 API 先创建 WAITING 实例；实际投递、状态核对及停止由 `DevTaskJob` 调用 `processPending` 完成。运行维护处理器未启动时，WAITING 不表示已经提交给 DS。隔离验收进程应只维护本次批准的实例编号，避免处理其他工作中的等待任务。运行顺序为保存完整配置、启用任务、提交唯一 `requestId`、推进维护并轮询实例；修改定义后使用新的修订号和保存版本。

`file-id` 是部署档案中的唯一受管资源引用，由平台映射到 DS 3.4.2 的 `resource-name`；不是客户端自由传入的文件路径，也不会把旧版 DS 的 id/res 对象直接交给新版插件。Worker 相对路径按 DS 3.4.2 资源契约登记。保存完整版本及发送前分别核查允许的资源映射和实际下载字节 SHA-256，单个资源设定大小上限。制品应采用包含摘要的不可覆盖资源名称，并在 DS 存储权限中禁止原地修改；发送前摘要校验不能替代 Worker 下载时的存储不可变性。修改资源、外部执行用户、项目、运行环境或队列后调整 revision 并重新保存任务；旧版本不会自动采用新配置。Python 资源使用相同登记方式，扩展名必须 `.py`、program-types 为 `[PYTHON]`，不填写入口类。只有 SQL 的环境可以将 artifacts 保持为空并仅允许对应 SQL 类型。部署配置默认 profiles 为空，因此没有运行环境时不能生成原生可运行版本或执行。

正式环境还须为外部执行账号设置 YARN ACL 或本地 Worker 隔离、专用账号、网络和存储保护；本接口的权限授予不会自行创建这些隔离措施。向需要保存和执行原生程序的角色明确授予原生执行权限，并结合现有保存、运行、调度及日志权限使用。

本仓库 `deploy/dolphinscheduler-local/` 已提供 DS 3.4.2、Flink 1.20.3 和 Spark 3.5.6 的本地真实引擎部署；`verification/engines.json` 记录了原生 FLINK/SPARK 插件 smoke 的成功结果。该证据证明对应本地插件和引擎能运行，不代表任意 DataForge 任务、停止观察、失败恢复或 YARN 环境已完成端到端验收。平台验收需分别保留批任务成功、持续流运行后停止、Spark 长批停止、故意失败及无凭据泄露的日志分页证据，并在每次修改后重新验证。

协议依据：[FlinkTask 3.4.2](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-task-plugin/dolphinscheduler-task-flink/src/main/java/org/apache/dolphinscheduler/plugin/task/flink/FlinkTask.java)、[SparkTask 3.4.2](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-task-plugin/dolphinscheduler-task-spark/src/main/java/org/apache/dolphinscheduler/plugin/task/spark/SparkTask.java)、[Hadoop 3.3.6 ResourceManager REST](https://hadoop.apache.org/docs/r3.3.6/hadoop-yarn/hadoop-yarn-site/ResourceManagerRest.html)。
