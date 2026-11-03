package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 通道白名单：只返回已注册 type，重复 type 启动失败
 */
class DataPushChannelRegistryTest {

    @Test
    @DisplayName("按 type 取已注册通道，未知 type 返回空")
    void getRegisteredOnly() throws Exception {
        DummyChannel http = new DummyChannel(1);
        DummyChannel db = new DummyChannel(2);
        DataPushChannelRegistry registry = newRegistry(List.of(http, db));
        registry.init();
        assertSame(http, registry.get(1));
        assertSame(db, registry.get(2));
        assertNull(registry.get(9));
    }

    @Test
    @DisplayName("重复通道类型启动失败")
    void duplicateTypeFails() throws Exception {
        DataPushChannelRegistry registry = newRegistry(List.of(new DummyChannel(1), new DummyChannel(1)));
        assertThrows(IllegalStateException.class, registry::init);
    }

    private static DataPushChannelRegistry newRegistry(List<DataPushChannel> channels) throws Exception {
        DataPushChannelRegistry registry = new DataPushChannelRegistry();
        Field field = DataPushChannelRegistry.class.getDeclaredField("channels");
        field.setAccessible(true);
        field.set(registry, channels);
        return registry;
    }

    private static final class DummyChannel implements DataPushChannel {
        private final Integer type;

        private DummyChannel(Integer type) {
            this.type = type;
        }

        @Override
        public Integer getType() {
            return type;
        }

        @Override
        public void push(PushPayload payload, String channelJson, StringBuilder log) {
            // no-op
        }
    }

}
