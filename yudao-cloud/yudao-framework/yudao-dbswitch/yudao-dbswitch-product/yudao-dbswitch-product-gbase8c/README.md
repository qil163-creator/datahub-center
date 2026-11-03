# GBase 8c 独立连接探测

本模块仅声明 `CONNECT`。元数据、DDL、读写和同步均为 `UNSUPPORTED`，声明不等于真实数据库认证。

当前默认配置精确指向原厂专用 JDBC 配置：驱动类 `com.gbase8c.Driver`，URL 协议 `jdbc:gbase8c://{host}:{port}/{database}`。原厂站点的 [GBase 8c 使用 JDBC 获取游标类型数据](https://www.gbase.cn/community/post/4142) 同时给出了这两个标识。[官方驱动下载中心](https://www.gbase.cn/download/gbase-8c?category=DRIVER_PACKAGE) 是驱动包的取得入口。

原厂的 [应用开发程序文档](https://www.gbase.cn/docs/gbase-8c/03%20%E5%BC%80%E5%8F%91%E8%80%85%E6%8C%87%E5%8D%97/%E5%BA%94%E7%94%A8%E5%BC%80%E5%8F%91%E7%A8%8B%E5%BA%8F/) 另有使用 `org.postgresql.Driver` 与 `jdbc:postgresql://` 的版本配置。二者必须作为匹配的驱动配置处理，不能从共享协议推断产品身份，也不能只更换驱动类而保留另一版本的 URL。

仓库不捆绑未核验的商业驱动。部署时应通过既有可信驱动包机制登记实际原厂 JAR、依赖及 SHA-256，确认驱动类、URL、服务器版本和认证配置匹配，再对真实 GBase 8c 实例测连。`profileId` 选择明确登记的驱动配置；没有对应驱动时返回 `MISSING_DEPENDENCY` / `SKIPPED`，不把端口可达当成连接成功。

本次仅完成协议实现、能力拒绝和缺依赖等契约测试；未取得真实 GBase 8c 测试实例，未将产品状态提升为 `VERIFIED`。
