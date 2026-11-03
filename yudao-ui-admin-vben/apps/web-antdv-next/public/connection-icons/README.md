# 连接图标来源

这些图标作为应用静态资源随前端发布，已知连接器类型通过共享 `ConnectionIcon` 组件按 `typeCode` 默认加载本地图标，旧种子配置不再请求已失效的历史上传地址。管理员上传的自定义图标仍优先显示。首批 PNG 为 256×256 透明背景图片，由本机已安装的矢量图标数据渲染（`@resvg/resvg-js`）；后续资源复用仓库既有图形或官方品牌资产，保留原图形和比例，不含脚本、外部图片或外部字体。

| 文件                    | 来源与原图标名                                                          | 许可及调整                                                                       |
| ----------------------- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `mysql.svg`、`postgresql.svg`、`mariadb.svg` | `src/views/dataforge/dev/task-product-icons.ts` 的同名产品图标 | 沿用仓库已记录的 SVG Logos / Simple Icons（CC0）；保留原图形、颜色及比例 |
| `doris.svg`、`oracle.svg`、`sqlserver.svg`、`clickhouse.svg`、`hive.svg` | `src/views/dataforge/dev/task-product-icons.ts` 的同名产品图标 | 同上；`currentColor` 固定为既有 `task-icon-model.ts` 的对应产品颜色，图形路径不变 |
| `dm.svg` | `src/views/modeling/logictable/assets/db-dm.svg` | 复用仓库既有达梦产品图标，保留原图形和配色 |
| `starrocks.png` | [StarRocks 官网 favicon](https://www.starrocks.io/21782839.fs1.hubspotusercontent-na1.net/hubfs/21782839/favicon-starrocks-1.ico.png) | 32px 官方产品符号，原文件保存 |
| `kingbase.ico` | [人大金仓官网 favicon](https://www.kingbase.com.cn/static/cn/img/favicon.ico) | 48px 官方图标，原文件保存 |
| `oscar.png` | [神通数据库官网 Logo](https://shentongdata.com/data/attachment/2019-12/2019121608431415700s.png) | 保留官方原图形和比例 |
| `highgo.svg` | [瀚高官网资源中的 `icon-logo`](https://www.highgo.com/_nuxt/BsGUcdq0.js) | 从官方 SVG sprite 提取原始 symbol，图形不变 |
| `opengauss.svg` | [openGauss 官方品牌资源](https://opengauss.org/category/brand/view/logo5.svg) | 原始 112×92 图形，补齐同尺寸 viewBox 以便等比缩放 |
| `oceanbase.svg` | [OceanBase 官方仓库](https://raw.githubusercontent.com/oceanbase/oceanbase/master/images/logo.svg) | 保留官方原图形和比例 |
| `greenplum.svg` | [Greenplum 官方归档仓库](https://raw.githubusercontent.com/greenplum-db/gpdb-archive/main/logo-greenplum.svg) | 保留官方原图形和比例 |
| `uxdb.png` | [优炫数据库官网 favicon](https://www.uxdb.net.cn/upload/uxdblogoico.ico) | 无损提取 ICO 内嵌的 120px PNG，不改变图形 |
| `sqlserver2000.png`    | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `microsoftsqlserver`    | CC0-1.0；复用 SQL Server 产品标志，设置固定红色，不声称是 2000 版专有标志         |
| `db2.png`              | 已安装 `@iconify/json@2.2.521` 中 IBM Carbon `11.86.0` 的 `ibm-db2-alt` | Apache-2.0；将单色填充固定为蓝色，路径不变                                        |
| `sqlite3.png`          | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `sqlite`                | CC0-1.0；将单色填充固定为蓝色                                                    |
| `mongodb.png`          | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `mongodb`               | CC0-1.0；将单色填充固定为绿色                                                    |
| `tidb.png`             | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `tidb`                  | CC0-1.0；将单色填充固定为红色                                                    |
| `tdengine.png`         | 本项目绘制的 TDengine 产品文字标识                                      | 沿用本仓库许可；非官方 Logo，不使用通用数据库图形代替产品名                       |
| `influxdb3.png`        | 已安装 `@iconify-json/logos@1.2.13` 的 `influxdb-icon`               | CC0-1.0；保留原有路径和颜色，标识 InfluxDB 产品系列                               |
| `influxdb2.png`        | 已安装 `@iconify-json/logos@1.2.13` 的 `influxdb-icon`               | CC0-1.0；InfluxDB 2.x 与 3 共用产品族标志                                          |
| `elasticsearch.png`    | 已安装 `@iconify-json/logos@1.2.13` 的 `elasticsearch`               | CC0-1.0；保留原有路径和颜色                                                       |
| `database.png`         | 已安装 `@iconify/json@2.2.521` 中 Lucide 的 `database`                | ISC / MIT（源自 Feather）；将描边固定为灰蓝色，仅作通用兜底                       |
| `hdfs.png`             | 已安装 `@iconify-json/logos@1.2.13` 的 `hadoop`                      | CC0-1.0；HDFS 属于 Hadoop 生态，使用 Hadoop 品牌标志标识                          |
| `redis.png`            | 已安装 `@iconify-json/logos@1.2.13` 的 `redis`                       | CC0-1.0；保留原有路径和颜色                                                       |
| `kafka.png`            | 已安装 `@iconify-json/logos@1.2.13` 的 `kafka-icon`                  | CC0-1.0；使用 Kafka 圆形标志而非文字组合                                          |
| `rabbitmq.png`         | 已安装 `@iconify-json/logos@1.2.13` 的 `rabbitmq-icon`               | CC0-1.0；保留原有路径和颜色                                                       |
| `rocketmq.png`         | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `apacherocketmq`       | CC0-1.0；将单色填充按其品牌色固定为橙红色                                        |
| `neo4j.png`            | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `neo4j`                 | CC0-1.0；将单色填充按其品牌色固定为蓝色                                          |
| `minio.png`            | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `minio`                 | CC0-1.0；将单色填充按其品牌色固定为红色                                          |
| `milvus.png`           | 已安装 `@iconify-json/logos@1.2.13` 的 `milvus-icon`                 | CC0-1.0；保留原有路径和颜色                                                       |
| `saphana.png`          | 已安装 `@iconify-json/logos@1.2.13` 的 `sap`                         | CC0-1.0；使用 SAP 品牌标志标识 SAP HANA 连接                                      |
| `odps.png`             | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `alibabacloud`         | CC0-1.0；将单色填充固定为阿里云品牌橙色；ODPS/MaxCompute 为阿里云产品             |
| `oss.png`              | 已安装 `@iconify-json/simple-icons@1.2.93` 的 `alibabacloud`         | CC0-1.0；同上，阿里云 OSS 与 ODPS 共用阿里云品牌标志                              |
| `gbase8c.png`          | 平台既有的 GBASE 品牌图标（由管理员上传的连接图标资源）                  | 沿用本仓库既有资源；仅代表 GBASE 产品族，不声称官方授权                           |
| `sybase.png`           | 本项目绘制的 Sybase 产品文字标识                                        | 沿用本仓库许可；非官方 Logo，不使用通用数据库图形代替产品名                       |
| `ftp.png`              | 本项目绘制的 FTP 协议文字标识                                           | 沿用本仓库许可；FTP 为协议名，无产品品牌标志                                      |
| `sftp.png`             | 本项目绘制的 SFTP 协议文字标识                                          | 沿用本仓库许可；SFTP 为协议名，无产品品牌标志                                     |
| `gaussdb.png`          | 本项目绘制的 GaussDB 产品文字标识                                       | 沿用本仓库许可；本机 Iconify 集合无 GaussDB 产品图标，不使用无关品牌标志代替      |
| `phoenix.png`          | 本项目绘制的 Apache Phoenix 产品文字标识                                | 沿用本仓库许可；本机 Iconify 集合只有 Phoenix 框架等无关图标，不使用其代替        |
| `presto.png`           | 本项目绘制的 PrestoDB 产品文字标识                                      | 沿用本仓库许可；本机 Iconify 集合的 Presto 标志为宽幅文字组合，小尺寸不可读        |

上游项目及许可来源：

- [Simple Icons](https://github.com/simple-icons/simple-icons)（[CC0-1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md)）。
- [SVG Logos](https://github.com/gilbarbara/logos)（[CC0-1.0](https://github.com/gilbarbara/logos/blob/main/LICENSE.txt)）。
- [IBM Carbon Icons](https://github.com/carbon-design-system/carbon/tree/main/packages/icons)；Apache-2.0 正文随本目录的 `APACHE-2.0.txt` 提供。
- [Lucide](https://github.com/lucide-icons/lucide)；ISC 与 Feather MIT 原声明随本目录的 `LUCIDE-LICENSE.txt` 提供。

## 生成与更新约定

- 生成的 PNG 使用 256×256 透明画布、内容居中；复用的官方资源保留原有图形和比例，由共享组件等比显示，不引入运行时网络请求。
- 新增或替换连接图标时，同步维护 `src/components/connection-icon/path.ts` 中的产品编码映射，并保持 `/connection-icons/` 路径按应用部署前缀解析。若同时修改数据库 `icon_path`，仍需新增编号迁移；本地图标映射不修改数据库配置。
- 图标加载顺序为自定义 `iconPath`、已知类型的本地图标、通用 PNG、内联 SVG。空值、内置 `/connection-icons/` 路径和严格匹配旧种子的 loopback 上传 URL 按类型使用本地图标。新类型可以直接上传图标，不需要新增前端映射；没有配置时使用通用图标。
- 类型编码只统一大小写与首尾空白，并显式兼容历史别名；不删除任意分隔符，不根据显示名称猜测产品。
- `GBASE8A` 与 `GBASE8C` 复用既有 `gbase8c.png` 的 GBase 产品族标识。
- 早期内置 SVG 图标由 `0209_dataforge_connection-icons-png.sql` 迁移为 PNG；这些历史数据库路径不影响现在按产品编码统一加载的图标。
- Sybase、TDengine 在本机完整 Iconify 集合中没有对应产品图标。已对官方来源做有界检索：Sybase 未取得可追溯的官方 SVG；[TDengine 官方仓库](https://github.com/taosdata/TDengine)虽有 `tdengine-logo.svg`，但未发现独立于仓库 AGPL-3.0 的图标许可，因此此处使用自制文字标识，没有复制该资源。

产品名称及商标归各自权利人所有；此目录的来源许可说明不表示获得商标授权或产品背书。
