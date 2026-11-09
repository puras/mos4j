package net.hegq.mos4j.examples.testkit;

import net.hegq.mos4j.model.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身自己的用例：示例的用例都靠它，替身坏了整套示例的用例都会假绿。
 */
class StubModelTest {

    @Test
    @DisplayName("按脚本逐轮返回")
    void returnsTheScriptedSteps() {
        StubModel model = StubModel.of(StubModel.text("一"), StubModel.text("二"));

        assertEquals("一", model.chat(Request.ofUser("q")).message().content());
        assertEquals("二", model.chat(Request.ofUser("q")).message().content());
    }

    @Test
    @DisplayName("脚本里放异常就真的抛")
    void throwsTheScriptedFailure() {
        StubModel model = StubModel.of(new IllegalStateException("炸了"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> model.chat(Request.ofUser("q")));
        assertEquals("炸了", thrown.getMessage());
    }

    @Test
    @DisplayName("记录每次请求：能断言模型看见了什么")
    void recordsTheRequests() {
        StubModel model = StubModel.of(StubModel.text("一"), StubModel.text("二"));

        model.chat(Request.ofUser("你好"));
        model.chat(Request.ofUser("再见"));

        assertEquals(2, model.requests().size());
        assertEquals("你好", model.requests().get(0).messages().get(0).content());
        assertEquals(List.of(), model.toolNamesOf(0));
    }

    @Test
    @DisplayName("脚本用完就说清楚（不是 IndexOutOfBounds）")
    void saysSoWhenTheScriptIsExhausted() {
        StubModel model = StubModel.of(StubModel.text("一"));

        model.chat(Request.ofUser("q"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> model.chat(Request.ofUser("q")));
        assertTrue(thrown.getMessage().contains("脚本已用完"), thrown.getMessage());
    }

    @Test
    @DisplayName("命中缓存返回同一个响应对象")
    void returnsTheSameResponseInstance() {
        var response = StubModel.text("同一个");
        StubModel model = StubModel.of(response);

        assertSame(response, model.chat(Request.ofUser("q")));
    }
}
