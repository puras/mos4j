package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ModelOption;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录的守护测试。
 *
 * <p>守护的口径（对齐 Go 参考实现 {@code mos.models.go}）：
 * 目录 fail-closed 校验、换模型<b>下一轮生效</b>、不重建智能体、凭据不进公开描述。
 */
class ModelCatalogTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 记录自己被调过的模型替身。 */
    private static final class CountingModel implements ChatModel {

        final String name;
        int calls;

        CountingModel(String name) {
            this.name = name;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            calls++;
            return new Response(Message.assistant(name), NO_USAGE, Response.FINISH_END_TURN);
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
        public Set<Capability> capabilities() {
            return Set.of(Capability.FUNCTION_CALLING);
        }
    }

    private static List<ModelOption> options(ChatModel... models) {
        List<ModelOption> options = new ArrayList<>();
        for (ChatModel model : models) {
            options.add(new ModelOption(model instanceof CountingModel counting
                    ? counting.name : "m", model));
        }
        return options;
    }

    @Test
    void aDuplicateIdIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Config.builder()
                        .models(options(new CountingModel("a"), new CountingModel("a")))
                        .build());

        assertTrue(error.getMessage().contains("重复"), error.getMessage());
    }

    @Test
    void aDefaultIdOutsideTheCatalogIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Config.builder()
                        .models(options(new CountingModel("a")))
                        .defaultModelId("nope")
                        .build());

        assertTrue(error.getMessage().contains("不在目录里"), error.getMessage());
    }

    @Test
    void aModelAndACatalogAtTheSameTimeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Config.builder()
                        .model(new CountingModel("solo"))
                        .models(options(new CountingModel("a")))
                        .build());

        assertTrue(error.getMessage().contains("二选一"), error.getMessage());
    }

    @Test
    void theFirstEntryIsTheDefaultUnlessToldOtherwise() {
        Agent agent = Agent.of(Config.builder()
                .models(options(new CountingModel("a"), new CountingModel("b")))
                .build());

        assertEquals("a", agent.modelId());
        assertEquals(2, agent.models().size());
    }

    @Test
    void theDefaultIdIsHonored() {
        Agent agent = Agent.of(Config.builder()
                .models(options(new CountingModel("a"), new CountingModel("b")))
                .defaultModelId("b")
                .build());

        assertEquals("b", agent.modelId());
    }

    @Test
    void theSwitchTakesEffectOnTheNextRound() throws IOException {
        CountingModel first = new CountingModel("a");
        CountingModel second = new CountingModel("b");
        Agent agent = Agent.of(Config.builder()
                .models(options(first, second))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "一");
        assertEquals(1, first.calls);
        assertEquals(0, second.calls);

        agent.setModel("b");
        agent.run(session, "二");

        assertEquals(1, first.calls, "换模型后不该再走旧模型");
        assertEquals(1, second.calls);
        assertEquals("b", agent.modelId());
    }

    @Test
    void switchingToAnUnknownIdFailsLoudly() {
        Agent agent = Agent.of(Config.builder()
                .models(options(new CountingModel("a")))
                .build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent.setModel("nope"));

        assertTrue(error.getMessage().contains("不在目录里"), error.getMessage());
    }

    @Test
    void aSingleModelSetupCannotSwitch() {
        Agent agent = Agent.of(Config.builder().model(new CountingModel("solo")).build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.setModel("b"));

        assertTrue(error.getMessage().contains("单模型模式"), error.getMessage());
        assertEquals("", agent.modelId());
    }

    @Test
    void thePublicDescriptionCarriesNoCredentials() {
        CountingModel model = new CountingModel("a");
        Agent agent = Agent.of(Config.builder()
                .models(List.of(new ModelOption("a", "展示名", model)))
                .build());

        String description = agent.describe().toString();

        assertTrue(description.contains("展示名"), description);
        assertTrue(description.contains("a"), description);
    }
}
