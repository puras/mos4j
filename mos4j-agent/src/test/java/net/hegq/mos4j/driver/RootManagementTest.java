package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期根管理（对位 Go 参考版的 {@code Agent.AddRoot} / {@code RemoveRoot} / {@code Roots}）。
 *
 * <p><b>为什么要有这层 API</b>：会话里的 {@code grant_access} 只能由模型发起、
 * 且要人批准；宿主侧的程序化授权（"这个任务允许写 /tmp/build"）需要一条正经入口。
 */
class RootManagementTest {

    /** 只会答话的假模型。 */
    private static final class SilentModel implements ChatModel {

        @Override
        public Response chat(Request request) {
            return new Response(Message.assistant("好的"), new Usage(1, 1, 1, 2, null), "stop");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    @Test
    @DisplayName("运行期可以加根与移除根：边界跟着变")
    void rootsCanBeAddedAndRemovedAtRuntime() throws IOException {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "roots-" + System.nanoTime()).toAbsolutePath());
        Path anchor = Files.createDirectories(base.resolve("anchor"));
        Path extra = Files.createDirectories(base.resolve("extra"));
        RootSet roots = RootSet.of(anchor);
        Agent agent = Agent.of(Config.builder()
                .model(new SilentModel())
                .fileSystem(FileSystemBoundary.of(new SandboxedFs(roots)))
                .roots(roots)
                .build());

        assertEquals(1, agent.roots().size(), "装配时只有锚点");

        agent.addRoot("data", extra);

        assertEquals(2, agent.roots().size());
        assertTrue(agent.roots().stream().anyMatch(r -> r.id().equals("data")));
        assertTrue(new SandboxedFs(roots).resolve("data/x.txt").startsWith(extra.toRealPath()),
                "加根后路径真的落到新根里");

        agent.removeRoot("data");

        assertEquals(1, agent.roots().size());
        assertTrue(agent.roots().stream().noneMatch(r -> r.id().equals("data")));
    }

    @Test
    @DisplayName("没装根集合时明确报错（不假装能管根）")
    void withoutARootSetItFailsLoudly() {        Agent agent = Agent.of(Config.builder()
                .model(new SilentModel())
                .build());

        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class, () -> agent.roots());
        assertTrue(error.getMessage().contains("根集合"), error.getMessage());
    }

    @Test
    @DisplayName("只读根与只写根：读操作与写操作各走各的")
    void readOnlyAndWriteOnlyRootsAreSeparate() throws IOException {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "roots-" + System.nanoTime()).toAbsolutePath());
        Path anchor = Files.createDirectories(base.resolve("anchor"));
        Path cache = Files.createDirectories(base.resolve("cache"));
        Path output = Files.createDirectories(base.resolve("output"));
        RootSet roots = RootSet.of(anchor);
        Agent agent = Agent.of(Config.builder()
                .model(new SilentModel())
                .fileSystem(FileSystemBoundary.of(new SandboxedFs(roots)))
                .roots(roots)
                .build());

        agent.addReadRoot("cache", cache);
        agent.addWriteRoot("out", output);
        Files.writeString(cache.resolve("dep.txt"), "1.0", java.nio.charset.StandardCharsets.UTF_8);

        SandboxedFs fs = new SandboxedFs(roots);
        assertEquals(cache.toRealPath().resolve("dep.txt"), fs.resolveExisting("cache/dep.txt"),
                "只读根能读");
        assertThrows(net.hegq.mos4j.fs.PathDeniedError.class,
                () -> fs.resolveForWrite("cache/dep.txt"), "只读根不能写");
        assertEquals(output.toRealPath().resolve("artifact.txt"),
                fs.resolveForWrite("out/artifact.txt"), "只写根能写");
        assertThrows(net.hegq.mos4j.fs.PathDeniedError.class,
                () -> fs.resolveExisting("out/artifact.txt"), "只写根不能读");
    }
}
