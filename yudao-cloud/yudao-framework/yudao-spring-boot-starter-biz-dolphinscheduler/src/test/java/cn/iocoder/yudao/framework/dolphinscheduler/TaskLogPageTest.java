package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.api.LogApi;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskLogPageTest {
    private final DolphinSchedulerHttp http = mock(DolphinSchedulerHttp.class);
    private final LogApi logs = new LogApi(http);

    private void respond(String message, int misleadingLineNum) {
        when(http.get(eq("/log/detail"), anyMap())).thenReturn(new JSONObject()
                .set("code", 0).set("data", new JSONObject().set("message", message).set("lineNum", misleadingLineNum)));
    }

    @Test void firstPageHeaderNeverAdvancesThePhysicalCursor() {
        respond("[LOG-PATH]: /internal/task.log, [HOST]: worker:1234\r\none\r\ntwo\r\n", 3);
        var page = logs.queryLogPage(9, 0, 200);
        assertThat(page.message()).isEqualTo("one\ntwo");
        assertThat(page.readLines()).isEqualTo(2);
        assertThat(page.nextSkipLineNum()).isEqualTo(2);
        assertThat(page.mayHaveMore()).isTrue();
        verify(http).get("/log/detail", DolphinSchedulerHttp.params("taskInstanceId", 9, "skipLineNum", 0, "limit", 200));
    }

    @Test void acceptsLfAndMissingFinalNewlineAndPreservesEmptyLines() {
        respond("first\n\nlast", 1);
        var page = logs.queryLogPage(9, 10, 200);
        assertThat(page.readLines()).isEqualTo(3);
        assertThat(page.nextSkipLineNum()).isEqualTo(13);
        assertThat(page.message()).isEqualTo("first\n\nlast");
    }

    @Test void emptyResponseAndHeaderOnlyPageDoNotMoveTheCursor() {
        respond("", 1);
        var empty = logs.queryLogPage(9, 20, 200);
        assertThat(empty.nextSkipLineNum()).isEqualTo(20);
        assertThat(empty.readLines()).isZero();
        assertThat(empty.mayHaveMore()).isFalse();
        respond("[LOG-PATH]: /internal/task.log, [HOST]: worker\r\n", 1);
        assertThat(logs.queryLogPage(9, 0, 200).nextSkipLineNum()).isZero();
    }

    @Test void aShortDsByteLimitedPageMustStillBePolled() {
        respond("large line [DS byte limit applied]\r\n", 1);
        var page = logs.queryLogPage(9, 2, 200);
        assertThat(page.mayHaveMore()).isTrue();
        assertThat(page.nextSkipLineNum()).isEqualTo(3);
    }

    @Test void oversizedUnicodeLineIsBoundedWithoutSkippingTheNextPhysicalLine() {
        respond("😀".repeat(40000) + "\r\nnext\r\n", 2);
        var page = logs.queryLogPage(9, 2, 200);
        assertThat(page.message().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(LogApi.MAX_PAGE_BYTES);
        assertThat(page.message()).endsWith("😀");
        assertThat(page.nextSkipLineNum()).isEqualTo(3);
        assertThat(page.truncated()).isTrue();
        assertThat(page.mayHaveMore()).isTrue();
    }

    @Test void malformedResponsesDoNotBecomeSuccessfulEmptyPages() {
        when(http.get(anyString(), anyMap())).thenReturn(new JSONObject().set("code", 0));
        assertThatThrownBy(() -> logs.queryLogPage(9, 0, 200)).isInstanceOf(DolphinSchedulerException.class);
        when(http.get(anyString(), anyMap())).thenReturn(new JSONObject().set("code", 400).set("msg", "sensitive"));
        assertThatThrownBy(() -> logs.queryLogPage(9, 0, 200)).isInstanceOf(DolphinSchedulerException.class)
                .hasMessageNotContaining("sensitive");
        when(http.get(anyString(), anyMap())).thenReturn(new JSONObject().set("code", 0).set("failed", true)
                .set("data", new JSONObject().set("message", "must not be displayed")));
        assertThatThrownBy(() -> logs.queryLogPage(9, 0, 200)).isInstanceOf(DolphinSchedulerException.class);
    }

    @Test void invalidBoundsNeverContactDs() {
        assertThatThrownBy(() -> logs.queryLogPage(0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.queryLogPage(9, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.queryLogPage(9, 0, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.queryLogPage(9, Integer.MAX_VALUE, 1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }
}
