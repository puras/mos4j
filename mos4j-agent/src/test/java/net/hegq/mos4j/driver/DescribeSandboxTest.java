package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.ResourceLimits;
import net.hegq.mos4j.sandbox.SandboxBoundary;
import net.hegq.mos4j.core.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Describe 快照必须诚实报告 J3 的边界（J3 验收项）。
 *
 * <p>只列「装了什么」会误导部署方：以为进程被沙箱隔离、以为文件路径受约束、
 * 以为版本令牌是强口径——这三样都会影响他们敢不敢上线。
 */
class DescribeSandboxTest {

    private static final class NullModel implements ChatModel {

        @Override
        public Response chat(Request request) {
            return new Response(Message.assistant("ok"), null, "stop");
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
    void describeReportsFileAndSandboxBoundaries() throws IOException {
        Path work = Files.createDirectories(
                Path.of("target", "test-tmp", "describe-" + System.nanoTime()).toAbsolutePath());
        SandboxedFs fs = new SandboxedFs(RootSet.of(work));
        ObserveGate gate = new ObserveGate();
        LocalRunner runner = LocalRunner.builder(work)
                .maxTimeout(Duration.ofSeconds(30))
                .limits(ResourceLimits.of(5, 10L))
                .build();

        Agent agent = Agent.of(Config.builder()
                .model(new NullModel())
                .fileSystem(FileSystemBoundary.of(fs))
                .sandbox(SandboxBoundary.of(runner))
                .build());

        List<String> features = agent.describe().features();
        List<String> caveats = agent.describe().caveats();

        assertTrue(features.stream().anyMatch(s -> s.contains("文件 jail=已装配")), features.toString());
        assertTrue(features.stream().anyMatch(s -> s.contains("路径校验=Lexical + Realpath 双校验")),
                features.toString());
        assertTrue(features.stream().anyMatch(s -> s.contains("写前观察门=已装配")), features.toString());
        assertTrue(features.stream().anyMatch(s -> s.contains("工作目录=" + work)), features.toString());
        assertTrue(features.stream().anyMatch(s -> s.contains("超时=min(请求值 ?? 缺省值, PT30S)")),
                features.toString());

        // 击杀强度必须原样出现（含「尽力而为」「非原子」「不等价于 Go」）
        assertTrue(caveats.stream().anyMatch(s -> s.contains("进程组击杀=") && s.contains("尽力而为")
                && s.contains("非原子") && s.contains("不等价于 Go")), caveats.toString());
        // 版本令牌口径必须说出来
        assertTrue(caveats.stream().anyMatch(s -> s.contains("版本令牌=")), caveats.toString());
        // 路径强制未装配必须显式说明
        assertTrue(caveats.stream().anyMatch(s -> s.contains("路径强制=未装配")), caveats.toString());
    }

    @Test
    void describeSaysWhenFileAndSandboxAreNotInstalled() {
        Agent agent = Agent.of(Config.builder().model(new NullModel()).build());

        List<String> caveats = agent.describe().caveats();

        assertTrue(caveats.stream().anyMatch(s -> s.contains("当前未装配文件 jail")), caveats.toString());
        assertTrue(caveats.stream().anyMatch(s -> s.contains("当前未装配命令沙箱")), caveats.toString());
    }
}
