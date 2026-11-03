import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.MariaDbSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServerSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.OscarSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServer2000SourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aSourceGeneration;

import java.sql.DriverManager;
import java.util.Arrays;

/** 仅供 DBA 显式运行的 Java 25 源码启动工具，不挂接应用启动，也不自动授权或创建数据库模式。 */
class SourceGenerationAdmin {
    public static void main(String[] args) throws Exception {
        ProductTypeEnum product = ProductTypeEnum.MARIADB;
        if (args.length >= 2 && "--product".equals(args[0])) {
            product = switch (args[1]) {
                case "MARIADB" -> ProductTypeEnum.MARIADB;
                case "TIDB" -> ProductTypeEnum.TIDB;
                case "SQLSERVER" -> ProductTypeEnum.SQLSERVER;
                case "OCEANBASE" -> ProductTypeEnum.OCEANBASE;
                case "ORACLE" -> ProductTypeEnum.ORACLE;
                case "OSCAR" -> ProductTypeEnum.OSCAR;
                case "SQLSERVER2000" -> ProductTypeEnum.SQLSERVER2000;
                case "GBASE8A" -> ProductTypeEnum.GBASE8A;
                default -> throw new IllegalArgumentException("Unsupported administrator generation product");
            };
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        if (args.length == 1 && "target".equals(args[0])) {
            String endpoint = required("DBSWITCH_RUNTIME_JDBC_URL");
            ConfiguredSourceGenerations.validateManagedEndpoint(product, endpoint);
            System.out.println(ConfiguredSourceGenerations.targetFingerprint(product,
                    endpoint, required("DBSWITCH_RUNTIME_USERNAME")));
            return;
        }
        if (args.length < 2 || !("init".equals(args[0]) && args.length == 2
                || "fence".equals(args[0]) && args.length == 4 || "rotate".equals(args[0]) && args.length == 5)) {
            throw new IllegalArgumentException("Usage: [--product MARIADB|TIDB|SQLSERVER|OCEANBASE|ORACLE|OSCAR|SQLSERVER2000|GBASE8A] target | init schema | fence schema instance generation | rotate schema instance generation nextGeneration");
        }
        // OceanBase 按已观测模式绑定租户身份；工具不向租户写入标记。
        if (product == ProductTypeEnum.OCEANBASE && "fence".equals(args[0])) {
            throw new IllegalArgumentException("OceanBase has no database-side fence: stop and drain every consumer, then rotate the deployed generation");
        }
        // Oracle 固定标识使用 PDB 身份（CON_NAME/CON_DBID/CON_ID）；工具不向 PDB 写入内容，因此没有需要隔离保护的标记。
        if (product == ProductTypeEnum.ORACLE && "fence".equals(args[0])) {
            throw new IllegalArgumentException("Oracle has no database-side fence: stop and drain every consumer, then rotate the deployed generation");
        }
        SourceGenerationPin.validateSchema(args[1]);
        String url = required("DBSWITCH_ADMIN_JDBC_URL"), username = required("DBSWITCH_ADMIN_USERNAME");
        ConfiguredSourceGenerations.validateManagedEndpoint(product, url);
        String password = System.getenv("DBSWITCH_ADMIN_PASSWORD");
        if (password == null) {
            if (System.console() == null) throw new IllegalStateException("Use an interactive password prompt or injected DBSWITCH_ADMIN_PASSWORD");
            char[] entered = System.console().readPassword("DBA password: ");
            if (entered == null) throw new IllegalStateException("Password input was cancelled");
            password = new String(entered); Arrays.fill(entered, '\0');
        }
        String effectiveUrl = product == ProductTypeEnum.MARIADB ? ConfiguredSourceGenerations.managedJdbcUrl(url) : url;
        try (var connection = DriverManager.getConnection(effectiveUrl, username, password)) {
            SourceGenerationPin result;
            if ("init".equals(args[0])) {
                result = product == ProductTypeEnum.MARIADB
                        ? MariaDbSourceGeneration.initialize(connection, args[1])
                        : product == ProductTypeEnum.TIDB ? TidbSourceGeneration.initialize(connection, args[1], 15)
                        : product == ProductTypeEnum.OCEANBASE ? OceanbaseSourceGeneration.initialize(connection, args[1], 15)
                        : product == ProductTypeEnum.ORACLE ? OracleSourceGeneration.initialize(connection, args[1], 15)
                        : product == ProductTypeEnum.OSCAR ? OscarSourceGeneration.initialize(connection, args[1], 15)
                        : product == ProductTypeEnum.SQLSERVER2000 ? SqlServer2000SourceGeneration.initialize(connection, args[1], 15)
                        : product == ProductTypeEnum.GBASE8A ? Gbase8aSourceGeneration.initialize(connection, args[1], 15)
                        : SqlServerSourceGeneration.initialize(connection, args[1], 15);
            } else {
                var expected = new SourceGenerationPin(args[1], args[2], args[3]);
                if ("fence".equals(args[0])) {
                    if (product == ProductTypeEnum.MARIADB) MariaDbSourceGeneration.fence(connection, expected);
                    else if(product == ProductTypeEnum.TIDB) TidbSourceGeneration.fence(connection, expected, 15);
                    else if(product == ProductTypeEnum.OSCAR) OscarSourceGeneration.fence(connection, expected, 15);
                    else if(product == ProductTypeEnum.SQLSERVER2000) SqlServer2000SourceGeneration.fence(connection, expected, 15);
                    else if(product == ProductTypeEnum.GBASE8A) Gbase8aSourceGeneration.fence(connection, expected, 15);
                    else SqlServerSourceGeneration.fence(connection, expected, 15);
                    System.out.println("FENCED: stop all consumers; do not resume with this generation.");
                    return;
                }
                result = product == ProductTypeEnum.MARIADB
                        ? MariaDbSourceGeneration.rotate(connection, expected, args[4])
                        : product == ProductTypeEnum.TIDB ? TidbSourceGeneration.rotate(connection, expected, args[4], 15)
                        : product == ProductTypeEnum.OCEANBASE ? OceanbaseSourceGeneration.rotate(connection, expected, args[4], 15)
                        : product == ProductTypeEnum.ORACLE ? OracleSourceGeneration.rotate(connection, expected, args[4], 15)
                        : product == ProductTypeEnum.OSCAR ? OscarSourceGeneration.rotate(connection, expected, args[4], 15)
                        : product == ProductTypeEnum.SQLSERVER2000 ? SqlServer2000SourceGeneration.rotate(connection, expected, args[4], 15)
                        : product == ProductTypeEnum.GBASE8A ? Gbase8aSourceGeneration.rotate(connection, expected, args[4], 15)
                        : SqlServerSourceGeneration.rotate(connection, expected, args[4], 15);
            }
            // 这些内容是供操作人员查看的命令输出，不属于应用日志；不输出端点或凭据。
            System.out.println("schema: " + result.schema());
            System.out.println("instance-id: " + result.instanceId());
            System.out.println("generation: " + result.generation());
            System.out.println(product == ProductTypeEnum.OCEANBASE
                    ? "No marker was written; the instance-id follows the verified tenant mode (MySQL server_uuid or Oracle tenant creation/switchover identity). Stop and drain consumers before deploying this pin."
                    : product == ProductTypeEnum.ORACLE
                    ? "No marker was written; the instance-id is derived from the PDB CON_NAME/CON_DBID/CON_ID. Restore or clone still requires rotation. Stop and drain consumers before deploying this pin."
                    : "Verify ACTIVE state and deploy the pin to every consumer before resuming.");
        } catch (java.sql.SQLException failure) {
            // JDBC 错误可能包含连接或 SQL 细节，命令默认输出应保持脱敏。
            System.err.println("Source generation operation failed; SQLState=" + failure.getSQLState()
                    + ", vendorCode=" + failure.getErrorCode() + ". Retain the same rotation token for reconciliation.");
            System.exit(2);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
}
