package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体关闭责任的守护测试。
 *
 * <p>守护的口径：宿主装进来的外部资源（MCP 连接、stdio 子进程）由智能体一并关掉，
 * 且关闭是幂等的、失败不吞。
 */
class AgentCloseTest {

    /** 最小模型替身（智能体要求模型：fail-closed）。 */
    private static final class StubModel implements ChatModel {

        @Override
        public Response chat(Request request) {
            return new Response(Message.assistant("好"), new Usage(0, 0, 0, 0, null),
                    Response.FINISH_END_TURN);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of();
        }
    }

    @Test
    void theAgentClosesWhatTheHostInstalled() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable resource = closed::incrementAndGet;

        Agent agent = Agent.of(Config.builder()
                .model(new StubModel())
                .closer(resource)
                .build());

        agent.close();

        assertEquals(1, closed.get());
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        Agent agent = Agent.of(Config.builder().model(new StubModel()).closer(closed::incrementAndGet).build());

        agent.close();
        agent.close();

        assertEquals(1, closed.get(), "关闭是幂等的");
    }

    @Test
    void everyResourceIsClosedEvenIfOneFails() {
        AtomicInteger second = new AtomicInteger();
        List<String> order = new ArrayList<>();
        Agent agent = Agent.of(Config.builder()
                .model(new StubModel())
                .closer(() -> {
                    order.add("first");
                    throw new IllegalStateException("关不掉");
                })
                .closer(() -> {
                    order.add("second");
                    second.incrementAndGet();
                })
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class, agent::close);

        assertTrue(error.getMessage().contains("关不掉"), error.getMessage());
        assertEquals(1, second.get(), "一个关不掉不能导致别的也不关");
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void anAgentWithoutResourcesClosesQuietly() throws Exception {
        Agent agent = Agent.of(Config.builder().model(new StubModel()).build());

        agent.close();
    }
}
