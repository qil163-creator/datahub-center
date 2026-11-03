package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPushLogBufferTest {

    @Test
    @DisplayName("超出上限时保留尾部并加截断标记")
    void trimKeepsTail() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            DataPushLogBuffer.append(log, "line-" + i, 80);
        }
        String snapshot = DataPushLogBuffer.snapshot(log, 80);
        assertTrue(snapshot.startsWith(DataPushLogBuffer.TRUNCATE_MARK));
        assertTrue(snapshot.contains("line-49"));
        assertTrue(snapshot.length() <= 80);
    }

}
