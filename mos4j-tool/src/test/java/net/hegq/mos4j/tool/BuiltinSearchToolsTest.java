package net.hegq.mos4j.tool;

import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.builtin.CurrentTimeTool;
import net.hegq.mos4j.tool.builtin.GlobTool;
import net.hegq.mos4j.tool.builtin.GrepTool;
import net.hegq.mos4j.tool.builtin.LsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.1–T5.4：grep（RE2J）/ glob / ls / current_time 的工具行为。
 */
class BuiltinSearchToolsTest {

    private Path base;
    private SandboxedFs fs;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "test-tmp", "search-" + System.nanoTime()).toAbsolutePath());
        fs = new SandboxedFs(RootSet.of(base));
        Files.createDirectories(base.resolve("src"));
        write("src/App.java", "public class App {\n    int readFileCount = 1;\n}\n");
        write("src/README.md", "# 项目说明\n这是一个文件读取工具\n");
        write("notes.txt", "read the manual\n");
        write("binary.bin", new byte[]{0x00, 0x01, 0x02});
    }

    private void write(String rel, String content) throws IOException {
        Files.writeString(base.resolve(rel), content, StandardCharsets.UTF_8);
    }

    private void write(String rel, byte[] bytes) throws IOException {
        Files.write(base.resolve(rel), bytes);
    }

    // ---- grep ----

    @Test
    @DisplayName("grep：命中格式 路径:行号:行内容")
    void grepReturnsPathLineContent() throws Exception {
        GrepTool grep = new GrepTool(fs);
        String out = (String) grep.execute("{\"pattern\":\"readFile\"}");

        assertTrue(out.contains("src/App.java:2:    int readFileCount = 1;"), out);
    }

    @Test
    @DisplayName("grep：非法正则直接报错，不静默当字面量")
    void grepRejectsInvalidRegex() {
        GrepTool grep = new GrepTool(fs);

        ToolException e = assertThrows(ToolException.class,
                () -> grep.execute("{\"pattern\":\"[abc\"}"));
        assertTrue(e.getMessage().contains("正则表达式非法"), e.getMessage());
    }

    @Test
    @DisplayName("grep：二进制文件跳过且注明")
    void grepSkipsBinary() throws Exception {
        GrepTool grep = new GrepTool(fs);
        String out = (String) grep.execute("{\"pattern\":\"read\"}");

        assertTrue(out.contains("binary.bin") == false, out);
        assertTrue(out.contains("二进制"), "要注明跳过了二进制: " + out);
    }

    @Test
    @DisplayName("grep：limit 截断并注明已达上限")
    void grepHonoursLimit() throws Exception {
        write("many.txt", "hit\nhit\nhit\nhit\nhit\n");
        GrepTool grep = new GrepTool(fs);
        String out = (String) grep.execute("{\"pattern\":\"hit\",\"path\":\"many.txt\",\"limit\":2}");

        long hits = java.util.Arrays.stream(out.split("\n"))
                .filter(l -> l.contains(":hit") || l.endsWith("hit")).count();
        assertTrue(hits <= 2, "命中不超过 2 条: " + out);
        assertTrue(out.contains("已达上限"), out);
    }

    @Test
    @DisplayName("grep：RE2J 正则工作（字符类、锚点）")
    void grepSupportsRe2Syntax() throws Exception {
        GrepTool grep = new GrepTool(fs);
        assertTrue(((String) grep.execute("{\"pattern\":\"^# 项目\"}"))
                .contains("src/README.md:1"));
        assertTrue(((String) grep.execute("{\"pattern\":\"[0-9]+\"}"))
                .contains("src/App.java"));
    }

    @Test
    @DisplayName("grep：glob 参数只搜匹配的文件")
    void grepFiltersByGlob() throws Exception {
        GrepTool grep = new GrepTool(fs);

        String mdOnly = (String) grep.execute("{\"pattern\":\"读取\",\"glob\":\"*.md\"}");
        assertTrue(mdOnly.contains("src/README.md"), mdOnly);
        assertFalse(mdOnly.contains("src/App.java"), mdOnly);
        assertFalse(mdOnly.contains("notes.txt"), mdOnly);

        String javaOnly = (String) grep.execute("{\"pattern\":\"readFile\",\"glob\":\"*.java\"}");
        assertTrue(javaOnly.contains("src/App.java"), javaOnly);
        assertFalse(javaOnly.contains("README.md"), javaOnly);
    }

    @Test
    @DisplayName("grep：glob 非法时报错")
    void grepRejectsInvalidGlob() {
        GrepTool grep = new GrepTool(fs);

        ToolException e = assertThrows(ToolException.class,
                () -> grep.execute("{\"pattern\":\"x\",\"glob\":\"[abc\"}"));
        assertTrue(e.getMessage().contains("glob 模式非法"), e.getMessage());
    }

    // ---- glob ----

    @Test
    @DisplayName("glob：不含 / 按文件名匹配")
    void globMatchesByFileName() throws Exception {
        GlobTool glob = new GlobTool(fs);
        String out = (String) glob.execute("{\"pattern\":\"*.java\"}");

        assertTrue(out.contains("src/App.java"), out);
        assertFalse(out.contains("README.md"), out);
    }

    @Test
    @DisplayName("glob：含 / 按相对路径匹配；** 跨目录")
    void globMatchesByPath() throws Exception {
        GlobTool glob = new GlobTool(fs);
        assertTrue(((String) glob.execute("{\"pattern\":\"src/**/*\"}"))
                .contains("src/App.java"));
        String onlyMd = (String) glob.execute("{\"pattern\":\"src/*.md\"}");
        assertTrue(onlyMd.contains("src/README.md"));
        assertFalse(onlyMd.contains("App.java"));
    }

    @Test
    @DisplayName("glob：字符类与 {a,b} 多选一")
    void globSupportsCharClassAndBraces() throws Exception {
        GlobTool glob = new GlobTool(fs);
        assertTrue(((String) glob.execute("{\"pattern\":\"*.{txt,md}\"}"))
                .contains("notes.txt"));
        assertTrue(((String) glob.execute("{\"pattern\":\"*.{txt,md}\"}"))
                .contains("src/README.md"));
        String chars = (String) glob.execute("{\"pattern\":\"[ns]otes.txt\"}");
        assertTrue(chars.contains("notes.txt"));
    }

    @Test
    @DisplayName("glob：非法模式报错")
    void globRejectsInvalidPattern() {
        GlobTool glob = new GlobTool(fs);

        ToolException e = assertThrows(ToolException.class,
                () -> glob.execute("{\"pattern\":\"{a\"}"));
        assertTrue(e.getMessage().contains("glob 失败"), e.getMessage());
    }

    @Test
    @DisplayName("glob：越界路径由 jail 拒绝")
    void globIsJailed() {
        GlobTool glob = new GlobTool(fs);

        assertThrows(ToolException.class, () -> glob.execute("{\"pattern\":\"*\",\"path\":\"../\"}"));
    }

    @Test
    @DisplayName("glob：limit 截断并注明已达上限")
    void globHonoursLimit() throws Exception {
        GlobTool glob = new GlobTool(fs);

        String out = (String) glob.execute("{\"pattern\":\"*\",\"limit\":2}");

        assertTrue(out.contains("已达上限"), out);
        assertTrue(out.split("\n").length <= 3, "最多 2 条匹配: " + out);
    }

    // ---- ls ----

    @Test
    @DisplayName("ls：含隐藏项、按名排序、格式 name\\ttype\\tsize")
    void lsListsSortedWithHidden() throws Exception {
        write(".hidden", "h");
        write("zeta.txt", "zz");
        LsTool ls = new LsTool(fs);
        String out = (String) ls.execute("{}");

        String[] lines = out.split("\n");
        assertTrue(lines[0].startsWith(".hidden\tfile\t1"), out);
        assertTrue(out.contains("src\tdir\t-"), out);
        assertTrue(out.contains("zeta.txt\tfile\t2"), out);
        // 排序验证
        int hidden = out.indexOf(".hidden");
        int zeta = out.indexOf("zeta.txt");
        assertTrue(hidden < zeta, "按名排序");
    }

    @Test
    @DisplayName("ls：文件与不存在目录报错")
    void lsRejectsNonDirectory() throws Exception {
        LsTool ls = new LsTool(fs);
        assertThrows(ToolException.class, () -> ls.execute("{\"path\":\"notes.txt\"}"));
        assertThrows(ToolException.class, () -> ls.execute("{\"path\":\"missing/\"}"));
    }

    @Test
    @DisplayName("ls：limit 截断并注明已达上限")
    void lsHonoursLimit() throws Exception {
        LsTool ls = new LsTool(fs);

        String out = (String) ls.execute("{\"limit\":2}");

        assertTrue(out.contains("已达上限"), out);
        // 截断后仍按名排序，取前两条
        assertTrue(out.startsWith("binary.bin\tfile\t3"), out);
    }

    @Test
    @DisplayName("参数 JSON 后面还有内容 → 解析失败（不再静默丢弃尾巴）")
    void trailingContentAfterTheObjectIsRejected() throws Exception {
        // Jackson 3 默认打开 FAIL_ON_TRAILING_TOKENS：只读第一个值、把尾巴静默丢掉的做法没了。
        // 这与 Go 的 json.Unmarshal 一致，也与机制 M9「不是合法 JSON 就显式报错」一致——
        // 之前 Jackson 2 的宽容会把模型发坏的参数当成合法参数，错误在更深处才炸。
        LsTool ls = new LsTool(fs);

        ToolException error = assertThrows(ToolException.class,
                () -> ls.execute("{\"path\":\".\"},\"x\":1"));

        assertTrue(error.getMessage().contains("参数解析失败"), error.getMessage());
    }

    // ---- current_time ----

    @Test
    @DisplayName("current_time：注入时钟可拨；不接受参数")
    void currentTimeUsesInjectedClock() throws Exception {
        Instant fixed = Instant.parse("2026-11-05T08:30:00Z");
        CurrentTimeTool tool = new CurrentTimeTool(() -> fixed);

        String out = (String) tool.execute("{}");
        assertTrue(out.contains("2026-11-05 08:30:00 UTC"), out);
        assertTrue(out.contains("1793867400000"), "epoch 毫秒: " + out);

        assertThrows(ToolException.class, () -> tool.execute("{\"x\":1}"));
    }
}
