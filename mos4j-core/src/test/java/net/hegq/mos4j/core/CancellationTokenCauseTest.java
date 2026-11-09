package net.hegq.mos4j.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 取消原因：{@code cancel()} / {@code cancel(Throwable)} / {@code cause()}。
 *
 * <p>每个用例守一条「不写也会出事」的规则：
 * <ul>
 *   <li><b>没被取消</b>时 {@code cause()} 必须是 {@code null}——「没取消」和
 *       「取消了但不知道原因」是两回事，后者不能被前者冒充；</li>
 *   <li><b>无原因取消</b>（{@code cancel()}）不产生原因——宿主没给原因，
 *       框架不能自己编一个；</li>
 *   <li><b>先到先得</b>：第一次取消带的原因生效，后来的取消不覆盖
 *       ——已经报出去的原因不能被后来的调用改写；</li>
 *   <li>取消原因要能<b>穿过</b>取消检查点（{@code throwIfCancelled()}），
 *       否则原因只在寄存器里躺着，调用方永远看不到。</li>
 * </ul>
 */
class CancellationTokenCauseTest {

    @Test
    void notCancelledHasNoCause() {
        CancellationToken token = CancellationToken.create();

        assertNull(token.cause(), "没被取消时不该有原因");
        assertEquals(false, token.isCancelled());
    }

    @Test
    void cancelWithoutReasonLeavesCauseNull() {
        CancellationToken token = CancellationToken.create();

        token.cancel();

        assertEquals(true, token.isCancelled());
        assertNull(token.cause(), "无原因取消不能编造原因");
    }

    @Test
    void cancelWithReasonKeepsTheReason() {
        CancellationToken token = CancellationToken.create();
        IllegalStateException reason = new IllegalStateException("用户点了停止");

        token.cancel(reason);

        assertEquals(true, token.isCancelled());
        assertSame(reason, token.cause(), "取消原因必须原样带出来");
    }

    @Test
    void firstReasonWins() {
        CancellationToken token = CancellationToken.create();
        IllegalStateException first = new IllegalStateException("用户点了停止");
        IllegalStateException second = new IllegalStateException("后来的原因");

        token.cancel(first);
        token.cancel(second);

        assertSame(first, token.cause(), "第一次取消的原因生效，后来的不覆盖");
    }

    @Test
    void cancelWithoutReasonDoesNotClearAnExistingReason() {
        CancellationToken token = CancellationToken.create();
        IllegalStateException reason = new IllegalStateException("上游超时");

        token.cancel(reason);
        token.cancel();

        assertSame(reason, token.cause());
    }

    @Test
    void throwIfCancelledReportsTheReason() {
        CancellationToken token = CancellationToken.create();
        IllegalStateException reason = new IllegalStateException("上游超时");

        token.cancel(reason);

        CancellationException thrown = assertThrows(CancellationException.class, token::throwIfCancelled);
        assertSame(reason, thrown.getCause(), "取消异常要带上原因，否则调用方只看到笼统的「已取消」");
    }

    @Test
    void callbacksStillRunOnce() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger fired = new AtomicInteger();

        token.onCancel(fired::incrementAndGet);
        token.cancel(new IllegalStateException("原因"));
        token.cancel(new IllegalStateException("另一个原因"));

        assertEquals(1, fired.get(), "取消回调只跑一次（重复取消不改写已发生的事实）");
    }

    @Test
    void noneTokenNeverHasACause() throws IOException {
        assertNull(CancellationToken.none().cause());
    }
}
