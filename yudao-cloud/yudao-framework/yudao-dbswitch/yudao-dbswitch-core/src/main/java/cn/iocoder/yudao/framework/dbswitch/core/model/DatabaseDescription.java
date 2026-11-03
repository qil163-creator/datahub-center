package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

/**
 * 数据库连接描述符信息定义(Database Description)
 *
 * @author jrl
 */
public class DatabaseDescription {

    protected ProductTypeEnum type;

    protected String host;

    protected int port;

    /**
     * 对于Oracle数据库的模式，可取范围为：sid,serviceName,TNSName三种
     */
    protected String mode;

    protected String dbname;

    protected String charset;

    protected String username;

    protected String password;

    /**
     * 初始化 {@code DatabaseDescription}。
     */
    public DatabaseDescription(String dbtype, String host, int port, String mode, String dbname,
            String charset, String username, String password) {
        this.type = ProductTypeEnum.valueOf(dbtype.toUpperCase());
        this.host = host;
        this.port = port;
        this.mode = mode;
        this.dbname = dbname;
        this.charset = charset;
        this.username = username;
        this.password = password;
    }

    /**
     * 返回类型。
     */
    public ProductTypeEnum getType() {
        return type;
    }

    /**
     * 返回主机。
     */
    public String getHost() {
        return host;
    }

    /**
     * 返回端口。
     */
    public int getPort() {
        return port;
    }

    /**
     * 返回模式。
     */
    public String getMode() {
        return mode;
    }

    /**
     * 返回数据库名称。
     */
    public String getDbname() {
        return dbname;
    }

    /**
     * 返回字符集。
     */
    public String getCharset() {
        return charset;
    }

    /**
     * 返回用户名。
     */
    public String getUsername() {
        return username;
    }

    /**
     * 返回密码。
     */
    public String getPassword() {
        return password;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "DatabaseDescription [type=" + type + ", host=" + host + ", port=" + port + ", mode="
                + mode + ", dbname=" + dbname + ", charset=" + charset + ", username=" + username
                + ", password=" + password + "]";
    }

}
