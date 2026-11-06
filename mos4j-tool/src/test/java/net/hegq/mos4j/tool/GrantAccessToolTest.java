package net.hegq.mos4j.tool;

import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.builtin.GrantAccessTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.5：grant_access——模型只有「申请」的能力，能不能加由宿主回调决定。
 */
class GrantAccessToolTest {

    private Path base;
    private Path outside;
    private RootSet roots;
    private SandboxedFs fs;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "test-tmp", "grant-" + System.nanoTime()).toAbsolutePath());
        outside = Files.createDirectories(
                base.resolve("outside-data"));
        roots = RootSet.of(base);
        fs = new SandboxedFs(roots);
    }

    @Test
    @DisplayName("批准：加根后模型可用挂载名访问")
    void approvedRootBecomesAccessible() throws Exception {
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> true);

        String out = (String) tool.execute(
                "{\"mount\":\"data\",\"dir\":\"" + outside + "\",\"reason\":\"读取数据目录\"}");

        assertTrue(out.contains("已开放"), out);
        // 挂载名立即生效：能列出挂载根下的内容
        assertTrue(Files.list(roots.anchor()).findAny().isPresent());
        SandboxedFs mountFs = new SandboxedFs(roots);
        mountFs.resolveExisting("data");
    }

    @Test
    @DisplayName("拒绝：不加根，且文案含「不许绕」语义")
    void rejectedApplicationDoesNotAddRoot() throws Exception {
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> false);

        String out = (String) tool.execute(
                "{\"mount\":\"data\",\"dir\":\"" + outside + "\",\"reason\":\"我想要\"}");

        assertTrue(out.contains("申请未通过"), out);
        assertTrue(out.contains("不要尝试用其他路径或方式访问同一目录"), "不许绕语义: " + out);
        assertFalse(roots.roots().stream().anyMatch(r -> "data".equals(r.id())),
                "拒绝后不能加根");
    }

    @Test
    @DisplayName("审批回调异常 = 拒绝（fail-closed），异常不外泄")
    void approverFailureIsFailClosed() throws Exception {
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> {
            throw new IllegalStateException("审批服务挂了");
        });

        String out = (String) tool.execute(
                "{\"mount\":\"data\",\"dir\":\"" + outside + "\",\"reason\":\"x\"}");

        assertTrue(out.contains("审批通道异常"), out);
        assertTrue(out.contains("fail-closed"), out);
        assertFalse(roots.roots().stream().anyMatch(r -> "data".equals(r.id())));
    }

    @Test
    @DisplayName("挂载名必须单段；锚点名不能用")
    void mountNameIsSingleSegment() {
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> true);

        assertThrows(ToolException.class, () -> tool.execute(
                "{\"mount\":\"a/b\",\"dir\":\"" + outside + "\",\"reason\":\"x\"}"));
        assertThrows(ToolException.class, () -> tool.execute(
                "{\"mount\":\"..\",\"dir\":\"" + outside + "\",\"reason\":\"x\"}"));
    }

    @Test
    @DisplayName("批准后目录不存在：报错且不加根（挂载失败）")
    void approvedButMissingDirFails() throws Exception {
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> true);
        Path missing = base.resolve("missing-dir");

        assertThrows(ToolException.class, () -> tool.execute(
                "{\"mount\":\"nope\",\"dir\":\"" + missing + "\",\"reason\":\"x\"}"));
        assertFalse(roots.roots().stream().anyMatch(r -> "nope".equals(r.id())));
    }

    @Test
    @DisplayName("批准不缓存：每次申请都走回调")
    void approvalIsNotCached() throws Exception {
        int[] calls = {0};
        GrantAccessTool tool = new GrantAccessTool(roots, fs, (path, reason) -> ++calls[0] > 0);

        tool.execute("{\"mount\":\"a\",\"dir\":\"" + outside + "\",\"reason\":\"x\"}");
        assertEquals(1, calls[0]);
    }
}
