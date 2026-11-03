# DataX 连接器与受管资源

集成任务使用 DS `DATAX` 原生任务。关系库 `read-db → insert-db` 继续使用 DS datasource ID；文件和文档的 customConfig=1 只编译无凭据配置，不从流程提取密码。

| 组件 | 当前编译边界 | 实际环境要求 |
| --- | --- | --- |
| read-csv / write-csv | txtfilereader / txtfilewriter，明确字段、UTF-8 等受控编码、CSV 引号转义 | 管理员 NONE 文件绑定、任务授权、容器目录挂载 |
| read-hdfs / write-hdfs | hdfsreader CSV/text；hdfswriter 仅 text；全量/新建文件 | 管理员 NONE HDFS 绑定和工作节点插件；不支持 Kerberos 凭据 |
| read-mongodb / write-mongodb | 集合全量读取/插入；无 SQL、更新或字段重排 | 管理员显式 ANONYMOUS 绑定；不能把密码缺失视为匿名 |
| read/write-ftp、read/write-odps | 配置可编辑；执行明确阻止 | 官方插件需内联密码/密钥，当前 DS 自定义 JSON 没有安全凭据引用适配 |
| read/write-hive | 当前未启用此连接器编译 | DS 的 HIVE 枚举会回退 rdbms 插件，尚未证明已安装驱动及实际写入语义 |
| read/write-json、Redis、消息队列、WebService | 当前未启用此连接器编译 | 官方 202308 工作节点包没有对应插件；不以表单存在宣称执行可用 |

六类无凭据连接器目前仅可直接互接。DataX 按位置传递行，因此输入输出字段数量、顺序、名称、类型必须一致。DECIMAL、TIMESTAMP、BINARY 等未证明无损的映射明确拒绝。原生关系库与 custom 文件插件混接需要安全的 Worker 凭据适配，尚不开放。

文件输出固定 `nonConflict`，输出文件名为前缀，由官方插件追加随机后缀。已有此前缀的文件时失败，不清空或覆盖目录。相对路径不接受绝对路径、URL、通配符、模板或 `..`。工作节点运行用户仍需最小文件权限；管理员绑定的挂载根目录必须防止任务用户建立越界符号链接，词法路径校验不能代替容器隔离。

管理员配置示例（按实际任务编号、目录与工作组调整；默认不授予任何任务）：

```yaml
dataforge:
  integration:
    datax:
      bindings:
        csv-fixture:
          allowed-task-ids: [7]
          connector-codes: [read-csv, write-csv]
          installed-plugins: [txtfilereader, txtfilewriter]
          authentication: NONE
          root-path: /home/datahub/fixtures
        hdfs-fixture:
          database-id: 17
          allowed-task-ids: [7]
          connector-codes: [read-hdfs, write-hdfs]
          installed-plugins: [hdfsreader, hdfswriter]
          authentication: NONE
          root-path: /warehouse/datahub
          default-fs: hdfs://namenode:8020
```

CSV 节点仅保存 `resourceBindingId: csv-fixture`、`path: source.csv` 与属性字段。网络节点继续使用平台 databaseId，后端先检查现有数据连接访问权限，再验证管理员按任务授权。管理员声明的 installed-plugins 必须与实际 Worker 工作组的发行包清单一致。

参考实现按本次实际部署版本核验：[DS 3.4.2 DataxTask](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-task-plugin/dolphinscheduler-task-datax/src/main/java/org/apache/dolphinscheduler/plugin/task/datax/DataxTask.java)、[官方 DataX 插件清单](https://github.com/alibaba/DataX/blob/master/pom.xml)、[HDFS writer](https://github.com/alibaba/DataX/blob/master/hdfswriter/doc/hdfswriter.md)、[CSV reader](https://github.com/alibaba/DataX/blob/master/txtfilereader/doc/txtfilereader.md)、[MongoDB writer](https://github.com/alibaba/DataX/blob/master/mongodbwriter/doc/mongodbwriter.md)。这些协议依据不代替服务联通验收；CSV 的 DS 实际运行结果单独记录在本地部署验收报告。
