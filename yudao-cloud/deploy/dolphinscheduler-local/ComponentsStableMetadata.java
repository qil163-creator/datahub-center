import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** 只清理本任务独立、已停止 H2 实例的瞬时协调状态，不读取或变更业务定义。 */
public final class ComponentsStableMetadata {
    public static void main(String[] args) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.startsWith("jdbc:h2:file:/var/lib/components-stable/metadata/dolphinscheduler;")) {
            throw new IllegalStateException("独立 DS 元数据位置不匹配");
        }
        // H2 文件独占连接失败时终止，不清理仍有 JVM 持有的数据库。
        try (Connection connection = DriverManager.getConnection(url, System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.executeUpdate("DELETE FROM t_ds_jdbc_registry_data WHERE data_type='EPHEMERAL'");
                sql.executeUpdate("DELETE FROM t_ds_jdbc_registry_lock");
                sql.executeUpdate("DELETE FROM t_ds_jdbc_registry_client_heartbeat");
                sql.executeUpdate("DELETE FROM t_ds_jdbc_registry_data_change_event");
            }
            connection.commit();
            System.out.println("COMPONENTS_STABLE_REGISTRY_RESET：已清理本实例停止后的协调状态");
        } catch (SQLException failure) {
            throw new IllegalStateException("独立 DS 协调状态无法独占清理，SQLState=" + failure.getSQLState());
        }
    }
}
