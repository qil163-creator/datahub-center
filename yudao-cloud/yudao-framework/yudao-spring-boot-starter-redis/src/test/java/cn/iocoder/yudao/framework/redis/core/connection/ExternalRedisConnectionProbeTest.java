package cn.iocoder.yudao.framework.redis.core.connection;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRedisConnectionProbeTest {
    @Test
    void isolatedAuthenticationSelectAndPingWithoutDataWrites() throws Exception {
        runFixture(false);
    }

    @Test
    void failedDatabaseSelectionDoesNotBecomeConnectedOrLeakPassword() throws Exception {
        runFixture(true);
    }

    private void runFixture(boolean denyDatabase) throws Exception {
        List<String> commands = new CopyOnWriteArrayList<>();
        String password = UUID.randomUUID().toString();
        try (ServerSocket listener = new ServerSocket(0);
             var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            var worker = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    while (true) {
                        String count = reader.readLine();
                        if (count == null) {
                            return;
                        }
                        List<String> request = new ArrayList<>();
                        for (int i = 0; i < Integer.parseInt(count.substring(1)); i++) {
                            reader.readLine();
                            request.add(reader.readLine());
                        }
                        String command = request.getFirst().toUpperCase();
                        commands.add(command);
                        String response = switch (command) {
                            case "AUTH" -> request.contains(password) ? "+OK\r\n" : "-WRONGPASS invalid credentials\r\n";
                            case "SELECT" -> denyDatabase ? "-ERR invalid DB index\r\n" : "+OK\r\n";
                            case "PING" -> "+PONG\r\n";
                            case "CLIENT" -> "+OK\r\n";
                            default -> "-ERR unexpected command\r\n";
                        };
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            ExternalRedisConnectionConfig config = new ExternalRedisConnectionConfig().setHost("127.0.0.1")
                    .setPort(listener.getLocalPort()).setDatabase(3).setUsername("isolated-probe")
                    .setPassword(password).setTimeoutMillis(1000);
            if (denyDatabase) {
                assertThatThrownBy(() -> ExternalRedisConnectionProbe.test(config))
                        .hasMessageContaining("数据库").hasMessageNotContaining(password).hasNoCause();
            } else {
                ExternalRedisConnectionProbe.test(config);
            }
            worker.get(10, TimeUnit.SECONDS);
            assertThat(commands).contains("AUTH", "SELECT").doesNotContain("SET", "DEL", "EVAL", "PUBLISH");
            if (denyDatabase) {
                assertThat(commands).doesNotContain("PING");
            } else {
                assertThat(commands).contains("PING");
            }
            assertThat(config.toString()).doesNotContain(password);
        }
    }
}
