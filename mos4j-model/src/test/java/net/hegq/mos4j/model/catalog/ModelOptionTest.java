package net.hegq.mos4j.model.catalog;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.ModelKind;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.chat.StreamEmitter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录项的守护测试：kind 与句柄一起报、校验 fail-closed、标签缺省。
 */
class ModelOptionTest {

    /** 不干活的 chat 模型替身（这里只验目录项的形状）。 */
    private static final class StubChat implements ChatModel {

        @Override
        public Response chat(Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    @Test
    void chatFactoryMarksKindAsChat() {
        ModelOption<ChatModel> option = ModelOption.chat("fast", new StubChat());

        assertEquals(ModelKind.CHAT, option.kind());
        assertEquals("fast", option.id());
    }

    @Test
    void labelDefaultsToId() {
        assertEquals("m", ModelOption.chat("m", new StubChat()).label());
        assertEquals("m", ModelOption.chat("m", null, new StubChat()).label());
        assertEquals("m", ModelOption.chat("m", "   ", new StubChat()).label());
    }

    @Test
    void explicitLabelIsKept() {
        assertEquals("展示名", ModelOption.chat("m", "展示名", new StubChat()).label());
    }

    @Test
    void kindIsRequired() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ModelOption<ChatModel>(null, "m", "m", new StubChat()));

        assertTrue(error.getMessage().contains("kind"), error.getMessage());
    }

    @Test
    void idIsRequired() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ModelOption<ChatModel>(ModelKind.CHAT, " ", "m", new StubChat()));

        assertTrue(error.getMessage().contains("ID"), error.getMessage());
    }

    @Test
    void modelIsRequired() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ModelOption<ChatModel>(ModelKind.CHAT, "m", "m", null));

        assertTrue(error.getMessage().contains("m"), error.getMessage());
    }

    @Test
    void handleTypeIsNotLockedToChat() {
        // kind 与句柄正交：将来 embedding / rerank 的句柄走同一个 record，不需要改结构。
        ModelOption<String> embedding = new ModelOption<>(ModelKind.EMBEDDING, "e", "嵌入", "handle");

        assertEquals(ModelKind.EMBEDDING, embedding.kind());
        assertEquals("handle", embedding.model());
    }
}
