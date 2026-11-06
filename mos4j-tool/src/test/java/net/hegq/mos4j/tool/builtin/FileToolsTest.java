package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.PathDeniedError;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.SchemaGenerator;
import net.hegq.mos4j.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置文件工具测试（T3.11；L3-15 / L3-16 / L3-17 + jail 联动）。
 */
class FileToolsTest {

    private Path root;
    private Path outside;
    private SandboxedFs fs;
    private ObserveGate gate;
    private ReadFileTool readTool;
    private WriteFileTool writeTool;
    private EditFileTool editTool;

    @BeforeEach
    void setUp() throws IOException {
        Path base = Path.of("target", "test-tmp", "tools-" + System.nanoTime()).toAbsolutePath();
        root = Files.createDirectories(base.resolve("root"));
        outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "机密", StandardCharsets.UTF_8);
        fs = new SandboxedFs(RootSet.of(root));
        gate = new ObserveGate();
        readTool = new ReadFileTool(fs, gate);
        writeTool = new WriteFileTool(fs, gate);
        editTool = new EditFileTool(fs, gate);
    }

    private static String args(String path, String content) {
        return "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}";
    }

    /** 一处替换的调用参数（对齐 Go 侧的 {@code edits[]} 形状）。 */
    private static String editArgs(String path, String oldText, String newText) {
        return "{\"path\":\"" + path + "\",\"edits\":[{\"old_string\":\"" + oldText
                + "\",\"new_string\":\"" + newText + "\"}]}";
    }

    // ---------- 读 ----------

    @Test
    void readReturnsContentAndRecordsObservation() throws Exception {
        Files.writeString(root.resolve("a.txt"), "你好", StandardCharsets.UTF_8);

        Object result = readTool.execute("{\"path\":\"a.txt\"}");

        assertEquals("你好", result);
        assertTrue(gate.observed(root.resolve("a.txt").toRealPath()));
    }

    @Test
    void readOutsideRootIsDenied() {
        PathDeniedError error = assertThrows(PathDeniedError.class,
                () -> readTool.execute("{\"path\":\"../outside/secret.txt\"}"));

        assertTrue(error.getMessage().contains("该操作允许的根"), error.getMessage());
    }

    @Test
    void readThroughSymlinkOutsideRootIsDenied() throws IOException {
        Files.createSymbolicLink(root.resolve("link"), outside.resolve("secret.txt").toRealPath());

        assertThrows(PathDeniedError.class, () -> readTool.execute("{\"path\":\"link\"}"));
    }

    // ---------- L3-15 写前观察三态 ----------

    @Test
    void l3_15_writeExistingFileWithoutReadIsDenied() throws IOException {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);

        ToolException error = assertThrows(ToolException.class,
                () -> writeTool.execute(args("a.txt", "新内容")));

        assertTrue(error.getMessage().startsWith("write 拒绝："), error.getMessage());
        assertTrue(error.getMessage().contains("已存在但未被读取过"), error.getMessage());
        assertEquals("原始", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void l3_15_writeAfterReadIsAllowed() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        Object result = writeTool.execute(args("a.txt", "新内容"));

        assertTrue(result.toString().contains("已写入"), result.toString());
        assertEquals("新内容", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void l3_15_writeAfterReadButChangedIsDenied() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");
        Files.writeString(root.resolve("a.txt"), "别人改的", StandardCharsets.UTF_8);

        ToolException error = assertThrows(ToolException.class,
                () -> writeTool.execute(args("a.txt", "新内容")));

        assertTrue(error.getMessage().contains("自读取后已被修改"), error.getMessage());
    }

    // ---------- L3-17 新建文件免读 ----------

    @Test
    void l3_17_newFileDoesNotRequireRead() throws Exception {
        Object result = writeTool.execute(args("fresh.txt", "新文件内容"));

        assertTrue(result.toString().contains("已写入"), result.toString());
        assertEquals("新文件内容", Files.readString(root.resolve("fresh.txt"), StandardCharsets.UTF_8));
    }

    // ---------- L3-16 edit 未读过 ----------

    @Test
    void l3_16_editWithoutReadIsDenied() throws IOException {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute(editArgs("a.txt", "原始", "改后")));

        assertTrue(error.getMessage().startsWith("edit 拒绝："), error.getMessage());
        assertTrue(error.getMessage().contains("编辑前必须先 read"), error.getMessage());
        assertEquals("原始", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editAfterReadReplacesTheText() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        Object result = editTool.execute(editArgs("a.txt", "原始", "改后"));

        assertTrue(result.toString().contains("已编辑"), result.toString());
        assertEquals("改后", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editWithMultipleMatchesFailsLoudly() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x\nx\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute(editArgs("a.txt", "x", "y")));

        assertTrue(error.getMessage().contains("出现 2 次"), error.getMessage());
    }

    @Test
    void editReplaceAllIsNotOffered() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x\nx\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        // edit 不做批量替换：参数里没有 replace_all，多处命中一律报错
        // （多余字段本身被忽略——参数形状容错；但 JSON 必须是合法的一个对象）
        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute("{\"path\":\"a.txt\",\"edits\":[{\"old_string\":\"x\","
                        + "\"new_string\":\"y\"}],\"replace_all\":true}"));

        assertTrue(error.getMessage().contains("出现 2 次"), error.getMessage());
    }

    @Test
    void editMatchesAcrossNormalizedLineEndings() throws Exception {
        Files.writeString(root.resolve("crlf.txt"), "第一行\r\n第二行\r\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"crlf.txt\"}");

        editTool.execute(editArgs("crlf.txt", "第一行\\n第二行", "新的"));

        String raw = Files.readString(root.resolve("crlf.txt"), StandardCharsets.UTF_8);
        assertEquals("新的\r\n", raw);
    }

    @Test
    void bomIsPreserved() throws Exception {
        Files.writeString(root.resolve("bom.txt"), "\uFEFFpackage demo;\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"bom.txt\"}");

        editTool.execute(editArgs("bom.txt", "package demo;", "package other;"));

        String raw = Files.readString(root.resolve("bom.txt"), StandardCharsets.UTF_8);
        assertTrue(raw.startsWith("\uFEFF"), "BOM 不应被吃掉: " + raw);
        assertTrue(raw.contains("package other;"), raw);
    }

    @Test
    void binaryFileIsRejected() throws Exception {
        Files.write(root.resolve("bin.dat"), new byte[]{0x61, 0x00, 0x62});

        ToolException error = assertThrows(ToolException.class,
                () -> readTool.execute("{\"path\":\"bin.dat\"}"));

        assertTrue(error.getMessage().contains("二进制"), error.getMessage());
    }

    @Test
    void writingNulBytesIsRejected() throws Exception {
        ToolException error = assertThrows(ToolException.class,
                () -> writeTool.execute("{\"path\":\"nul.txt\",\"content\":\"a\\u0000b\"}"));

        assertTrue(error.getMessage().contains("二进制"), error.getMessage());
        assertFalse(Files.exists(root.resolve("nul.txt")), "被拒的内容不应落盘");
    }

    @Test
    void editingInNulBytesIsRejected() throws Exception {
        Files.writeString(root.resolve("edit-nul.txt"), "hello\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"edit-nul.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute(editArgs("edit-nul.txt", "hello", "he\\u0000llo")));

        assertTrue(error.getMessage().contains("二进制"), error.getMessage());
        assertEquals("hello\n", Files.readString(root.resolve("edit-nul.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editWithMissingOldStringFailsLoudly() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute(editArgs("a.txt", "不存在", "x")));

        assertTrue(error.getMessage().contains("没有找到"), error.getMessage());
    }

    // ---------- edit 批量（对齐 Go 的 edits[]） ----------

    @Test
    void editAppliesSeveralEditsInOneCall() throws Exception {
        Files.writeString(root.resolve("a.txt"), "甲\n乙\n丙\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        Object result = editTool.execute("{\"path\":\"a.txt\",\"edits\":["
                + "{\"old_string\":\"甲\",\"new_string\":\"A\"},"
                + "{\"old_string\":\"丙\",\"new_string\":\"C\"}]}");

        assertTrue(result.toString().contains("替换 2 处"), result.toString());
        assertEquals("A\n乙\nC\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editLocatesEveryOldStringAgainstTheOriginalContent() throws Exception {
        // 两处 old_string 都在原文里唯一；第一处的新文会让第二处在改后的文本里出现两次，
        // 所以只能在原文里定位（顺序不影响定位）
        Files.writeString(root.resolve("a.txt"), "甲\n乙\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        editTool.execute("{\"path\":\"a.txt\",\"edits\":["
                + "{\"old_string\":\"甲\",\"new_string\":\"乙\"},"
                + "{\"old_string\":\"乙\",\"new_string\":\"丙\"}]}");

        assertEquals("乙\n丙\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editRejectsOverlappingEdits() throws Exception {
        Files.writeString(root.resolve("a.txt"), "abcdef\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute("{\"path\":\"a.txt\",\"edits\":["
                        + "{\"old_string\":\"abcd\",\"new_string\":\"X\"},"
                        + "{\"old_string\":\"cdef\",\"new_string\":\"Y\"}]}"));

        assertTrue(error.getMessage().contains("重叠"), error.getMessage());
        assertEquals("abcdef\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editAcceptsASingleEditObject() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        editTool.execute("{\"path\":\"a.txt\",\"edits\":{\"old_string\":\"原始\",\"new_string\":\"改后\"}}");

        assertEquals("改后\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editAcceptsAJsonStringOfEdits() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        editTool.execute("{\"path\":\"a.txt\",\"edits\":\"[{\\\"old_string\\\":\\\"原始\\\","
                + "\\\"new_string\\\":\\\"改后\\\"}]\"}");

        assertEquals("改后\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editRejectsIdenticalOldAndNew() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute(editArgs("a.txt", "原始", "原始")));

        assertTrue(error.getMessage().contains("无需编辑"), error.getMessage());
    }

    @Test
    void editReportsWhichEditFailed() throws Exception {
        Files.writeString(root.resolve("a.txt"), "甲\n乙\n", StandardCharsets.UTF_8);
        readTool.execute("{\"path\":\"a.txt\"}");

        ToolException error = assertThrows(ToolException.class,
                () -> editTool.execute("{\"path\":\"a.txt\",\"edits\":["
                        + "{\"old_string\":\"甲\",\"new_string\":\"A\"},"
                        + "{\"old_string\":\"不存在\",\"new_string\":\"X\"}]}"));

        assertTrue(error.getMessage().contains("第 2 处"), error.getMessage());
        // 失败时不得留下半改状态
        assertEquals("甲\n乙\n", Files.readString(root.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void editSchemaDeclaresAnEditsArray() {
        Map<String, Object> schema = SchemaGenerator.generate(EditFileTool.Params.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> edits = (Map<String, Object>) properties.get("edits");
        assertEquals("array", edits.get("type"), schema.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) edits.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        assertTrue(itemProperties.containsKey("old_string"), schema.toString());
        assertTrue(itemProperties.containsKey("new_string"), schema.toString());
    }

    // ---------- 越界写入 ----------
    @Test
    void writeOutsideRootIsDenied() {
        assertThrows(PathDeniedError.class, () -> writeTool.execute(args("../outside/x.txt", "内容")));
    }

    @Test
    void writeThroughSymlinkMiddleComponentIsDenied() throws IOException {
        Files.createSymbolicLink(root.resolve("escape"), outside.toRealPath());

        assertThrows(PathDeniedError.class, () -> writeTool.execute(args("escape/new.txt", "内容")));
    }

    @Test
    void missingParameterIsReported() {
        assertThrows(ToolException.class, () -> writeTool.execute("{\"content\":\"x\"}"));
        assertThrows(ToolException.class, () -> readTool.execute("{}"));
    }

    @Test
    void extraParametersAreTolerated() throws Exception {
        Files.writeString(root.resolve("a.txt"), "原始", StandardCharsets.UTF_8);

        Object result = readTool.execute("{\"path\":\"a.txt\",\"unknown\":123}");

        assertEquals("原始", result);
    }

    @Test
    void writeIsAtomicAndLeavesNoTempFile() throws Exception {
        Object result = writeTool.execute(args("a.txt", "内容"));

        assertFalse(result.toString().contains(".tmp"));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }
}
