# SeaTunnel 3.0 原生作业验收

此工具使用官方 `apache/seatunnel:3.0.0`，新建独立随机名称及归属标签的容器与网络，只将 REST 端口绑定宿主机 `127.0.0.1`。无需业务数据库、凭据、宿主目录挂载或额外驱动。

JSON 与 HOCON 各提交一个 `FakeSource -> Sql -> Console` 作业，均输出 3 行并等待 `FINISHED`。入口直接调用真实 `NativeSeatunnelConfig.validate` 和 `SeatunnelRestApi`；透明回环代理记录并逐字节对比提交体，报告同时记录原文件和请求的 SHA-256。配置中的 `env.job.name` 与查询参数不同，提交结果及终态必须采用查询参数的稳定名称。

在后端仓库根目录执行，先设置当前项目要求的 JDK 25：

```powershell
$env:JAVA_HOME = '<本机 JDK 25 目录>'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn -pl :yudao-spring-boot-starter-biz-seatunnel -am test '-Dtest=NativeSeatunnelConfigTest,SeatunnelRestApiTest' '-Dsurefire.failIfNoSpecifiedTests=false'
docker pull apache/seatunnel:3.0.0
python yudao-framework/yudao-spring-boot-starter-biz-seatunnel/src/test/resources/native-job/test_native_job.py --java-home $env:JAVA_HOME
```

Python 需 3.9 或更新版本。脚本从当前 Surefire 报告读取 Java 测试类路径，编译验收入口到忽略的 `target/seatunnel-native`；不会修改生产代码。无密报告位于 `target/seatunnel-native/report.json`，每种格式另有带本次随机编号的详细结果。

成功或失败都会先核对精确名称与归属标签，再删除本次容器、匿名卷及网络；不会删除其他 Docker 资源。若进程被强制终止，可使用同一命令加 `--cleanup`，按照保留的 `context.json` 清理。输出目录可通过 `--directory target/<其他目录>` 指定，必须位于仓库 `target` 内。

本验收证明两种格式的原文提交、转换执行、名称覆盖和作业完成；不证明所有连接器、生产凭据、CDC 恢复或业务授权。HOCON 的语法、include 和替换由 SeaTunnel 原生解释，本地组件不解析或读取引用资源。
