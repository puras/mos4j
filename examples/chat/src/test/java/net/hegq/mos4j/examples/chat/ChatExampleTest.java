package net.hegq.mos4j.examples.chat;

import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话示例的用例：会话恢复、审批问人、拒绝后不写盘。
 */
class ChatExampleTest {

    private Path tempDir() throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", "example-chat-" + System.nanoTime()));
    }

    /** 记录被问到的审批请求，按剧本答复。 */
    private static final class RecordingApproval implements ApprovalChannel {

        private final List<ApprovalRequest> asked = new ArrayList<>();
        private final ApprovalResult answer;

        RecordingApproval(ApprovalResult answer) {
            this.answer = answer;
        }

        @Override
        public ApprovalResult request(ApprovalRequest request) {
            asked.add(request);
            return answer;
        }
    }

    @Test
    @DisplayName("多轮：同一会话 id 连跑两轮，历史累积在会话日志里")
    void multiTurnKeepsHistory() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("第一轮"), StubModel.text("第二轮"));
        Mos4j mos4j = ChatExample.assemble(dir.resolve("sessions"), model,
                request -> ApprovalResult.approved());

        mos4j.run("s1", "第一句");
        mos4j.run("s1", "第二句");

        var session = mos4j.session("s1");
        List<String> users = session.events().stream()
                .filter(e -> e.kind() == EventKind.USER)
                .map(e -> e.text())
                .toList();
        assertEquals(List.of("第一句", "第二句"), users, "两轮的用户输入都在日志里");
        assertEquals(2, model.requests().size());
        assertTrue(model.requests().get(1).messages().size() > model.requests().get(0).messages().size(),
                "第二轮看得见第一轮（上下文由事件投影累积）");
    }

    @Test
    @DisplayName("重开会话：按 id 重开即恢复（不用宿主自己拼历史）")
    void reopeningRestoresTheSession() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("第一轮"));
        Mos4j mos4j = ChatExample.assemble(dir.resolve("sessions"), model,
                request -> ApprovalResult.approved());
        mos4j.run("s1", "第一句");

        Mos4j reopened = ChatExample.assemble(dir.resolve("sessions"), model,
                request -> ApprovalResult.approved());
        var session = reopened.session("s1");

        assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.USER),
                "重开后历史还在");
    }

    @Test
    @DisplayName("审批：写文件前问人；被拒就不写盘（但这一轮不失败）")
    void approvalGatesTheWrite() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.toolCall("c1", "write", "{\"path\":\"a.txt\",\"content\":\"你好\"}"),
                StubModel.text("没写"));
        RecordingApproval approval = new RecordingApproval(ApprovalResult.rejected("用户拒绝"));
        Mos4j mos4j = ChatExample.assemble(dir.resolve("sessions"), model, approval);

        String reply = mos4j.run("s1", "写个文件");

        assertEquals(1, approval.asked.size(), "写文件前问了人");
        assertEquals("write", approval.asked.get(0).toolName());
        assertEquals("没写", reply, "被拒后模型自己收尾，这一轮不算失败");
        assertFalse(Files.exists(dir.resolve("a.txt")), "被拒就不该有文件");
    }

    @Test
    @DisplayName("审批不可用：归一为不可用（不伪装成用户拒绝）")
    void unavailableApprovalIsNotARejection() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.toolCall("c1", "write", "{\"path\":\"a.txt\",\"content\":\"你好\"}"),
                StubModel.text("没写"));
        RecordingApproval approval = new RecordingApproval(ApprovalResult.unavailable("没人应答"));
        Mos4j mos4j = ChatExample.assemble(dir.resolve("sessions"), model, approval);

        mos4j.run("s1", "写个文件");

        var session = mos4j.session("s1");
        Event decided = session.events().stream()
                .filter(e -> e.kind() == EventKind.APPROVAL_DECIDED)
                .findFirst()
                .orElseThrow();
        assertEquals(ApprovalOutcome.UNAVAILABLE.wire(), decided.approval().outcome(),
                "不可用 ≠ 拒绝（两者在日志里必须分得清）");
    }
}
