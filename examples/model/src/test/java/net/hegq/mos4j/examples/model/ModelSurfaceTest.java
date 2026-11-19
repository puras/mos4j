package net.hegq.mos4j.examples.model;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.model.ModelKind;
import net.hegq.mos4j.model.StatusError;
import net.hegq.mos4j.model.catalog.ModelOption;
import net.hegq.mos4j.model.catalog.ModelRegistry;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.decorate.CallRecord;
import net.hegq.mos4j.model.decorate.ChatModelDecorator;
import net.hegq.mos4j.model.retry.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配面的用例：注册表 / 目录 / 装饰器叠加。
 *
 * <p>全部不碰网络：注册表装配只构造对象（不发请求）、横切能力跑在脚本化替身上。
 * 守的是<b>口径</b>而不是报文——报文层由 mos4j-model 的 L1 语料守。
 *
 * <p><b>异步任务与产物不在本类</b>：它们服务的是非 chat 的 kind（图像 / 视频 / 文档解析），
 * chat 示例没有那条生命周期；它们的语义由 mos4j-model 的 {@code TaskHandleTest} 与
 * {@code ArtifactTest} 守。
 */
class ModelSurfaceTest {

    // ------------------------------------------------------------------ 装配

    @Test
    @DisplayName("注册表：三个方言都注册了，按名字装配；未注册的名字 fail-closed 并列出已有名字")
    void registryAssemblesByProviderName() throws Exception {
        ModelRegistry registry = ModelSurface.registry();

        assertEquals(List.of("anthropic", "gemini", "openai"), registry.names());

        // 构造不发请求：这里验证的是「名字 → 工厂」这条通路，不是网络
        ChatModel openai = registry.create("openai",
                ModelSurface.config(null, "http://127.0.0.1:1/v1", "gpt-test"));
        assertNotNull(openai);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.create("llama.cpp-自造协议", ModelSurface.config("k", null, "m")),
                "未注册的名字不静默挑一个默认协议");
        assertTrue(error.getMessage().contains("已注册"), "错误里要说清有哪些已注册: "
                + error.getMessage());
        assertTrue(error.getMessage().contains("openai"), "已注册清单里要有 openai");
    }

    @Test
    @DisplayName("装配入口：已知协议装得出来，未知协议当场拒绝")
    void assembleFailsClosedOnUnknownProtocol() throws Exception {
        assertNotNull(ModelExample.assemble("openai", "问"),
                "已知协议从注册表装得出来（构造不发请求）");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ModelExample.assemble("llama.cpp-自造协议", "问"));
        assertTrue(error.getMessage().contains("已注册"),
                "报错要给出可选的协议名: " + error.getMessage());
    }

    @Test
    @DisplayName("目录项：kind 与句柄一起报，标签空则用 ID")
    void catalogReportsKindAndLabel() {
        StubModel model = StubModel.of(StubModel.text("ok"));

        List<ModelOption<ChatModel>> catalog = ModelSurface.catalog(model, model, model);

        assertEquals(3, catalog.size());
        for (ModelOption<ChatModel> option : catalog) {
            assertEquals(ModelKind.CHAT, option.kind(), "chat 目录项的 kind 是 CHAT");
            assertNotNull(option.model(), "目录项带着句柄");
        }
        assertEquals("OpenAI 方言", catalog.get(0).label());
        assertEquals("openai", catalog.get(0).id(), "换模型时用 ID 指认");

        ModelOption<ChatModel> noLabel = ModelOption.chat("gemini", model);
        assertEquals("gemini", noLabel.label(), "标签空 = 用 ID");
    }

    // ------------------------------------------------------------ 横切能力

    @Test
    @DisplayName("装饰器叠加：重试在内层、观测在外层——一次宿主调用只记一条，瞬时错误重试后成功")
    void decoratorsComposeInDeclaredOrder() {
        StubModel inner = StubModel.of(
                new StatusError("openai", 503, "服务端炸了"),   // 瞬时错误：值得重试
                StubModel.text("重试之后成功"));
        List<CallRecord> records = new ArrayList<>();

        ChatModel decorated = ModelSurface.decorate(inner, null, Duration.ofSeconds(5),
                RetryPolicy.builder().maxAttempts(3).initialBackoff(Duration.ZERO).build(),
                records::add);

        String answer = ModelExample.chatOnce(decorated, "问");

        assertEquals("重试之后成功", answer);
        assertEquals(2, inner.requests().size(), "内层真的被调了两次（一次失败 + 一次重试）");
        assertEquals(1, records.size(), "观测在外层：一次宿主调用只记一条（I2「日志即事实」）");
        assertTrue(records.get(0).succeeded(), "记录的是最终结果");
    }

    @Test
    @DisplayName("不可重试的错误直接上抛：4xx 参数错不重试（重试没有意义）")
    void nonTransientErrorIsNotRetried() {
        StubModel inner = StubModel.of(
                new StatusError("openai", 400, "参数不对"),
                StubModel.text("不该走到这里"));
        ChatModel decorated = ModelSurface.decorate(inner, null, Duration.ofSeconds(5),
                RetryPolicy.builder().maxAttempts(3).initialBackoff(Duration.ZERO).build(),
                null);

        StatusError error = assertThrows(StatusError.class,
                () -> ModelExample.chatOnce(decorated, "问"));

        assertEquals(400, error.statusCode());
        assertEquals(1, inner.requests().size(), "4xx 不重试：只调了一次");
    }

    @Test
    @DisplayName("降级：主模型失败切备用模型；能力集是并集（备用能顶上的能力要对外宣称）")
    void fallbackTakesOverAndMergesCapabilities() {
        StubModel primary = StubModel.of(new StatusError("openai", 400, "挂了"));
        StubModel backupInner = StubModel.of(StubModel.text("备用的回答"));

        ChatModel decorated = ModelSurface.decorate(primary, new CapabilityStub(backupInner),
                Duration.ofSeconds(5), null, null);

        assertEquals("备用的回答", ModelExample.chatOnce(decorated, "问"));
        assertTrue(decorated.supports(Capability.STREAMING),
                "能力集是并集：主模型不支持流式，但备用支持");
    }

    @Test
    @DisplayName("本地缓存：同一请求第二次不再打服务端；不同请求不共用答案；失败不缓存")
    void cacheHitsOnlyIdenticalNonStreamRequests() {
        StubModel inner = StubModel.of(
                StubModel.text("第一次回答"),
                StubModel.text("另一个问题的回答"),
                new StatusError("openai", 503, "炸了"));

        var cached = ModelSurface.withCache(inner, Duration.ofMinutes(1));
        assertEquals("第一次回答", ModelExample.chatOnce(cached, "同一个问题"));
        assertEquals("第一次回答", ModelExample.chatOnce(cached, "同一个问题"));
        assertEquals(1, inner.requests().size(), "命中缓存就不再调服务端");

        assertEquals("另一个问题的回答", ModelExample.chatOnce(cached, "换个问题"),
                "参数变了就是不同请求，不拿旧答案糊弄新问题");
        assertEquals(2, inner.requests().size());

        assertThrows(StatusError.class, () -> ModelExample.chatOnce(cached, "会失败的问题"));
        assertEquals(2, cached.size(), "失败不缓存（错误不是答案）");
    }

    @Test
    @DisplayName("主程序的装配面演示：目录 / 装饰一套走完，不碰网络")
    void mainAssemblyDemoRunsOnStub() {
        StubModel model = StubModel.of();

        // 这几段都是「口径演示」，不需要真实调用；不抛异常就说明形状都对得上
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ModelExample.printAssembly(model));
    }

    /** 给替身补上流式能力位（验证降级后能力集是并集）。 */
    private static final class CapabilityStub extends ChatModelDecorator {
        CapabilityStub(ChatModel delegate) {
            super(delegate);
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of(Capability.STREAMING);
        }
    }
}
