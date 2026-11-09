package net.hegq.mos4j.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文文件发现（候选名优先级、扫描顺序、向上递归、同目录 override、
 * 真实路径去重、字节预算、目录 fail-closed）。
 *
 * <p><b>框架不碰文件系统</b>：这里用一个内存 Map 当文件系统，规则照样可测。
 * <p>规则与 Go 参考版 {@code agent/contextfile} 同口径（候选名名单、扫描顺序、
 * 预算 32KB、行边界优先裁剪、真实路径去重、目录不存在即失败）。
 */
class ContextFilesTest {

    @Test
    @DisplayName("候选名优先级：同目录里 AGENTS.override.md 赢过 AGENTS.md")
    void candidatePriorityWins() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.override.md", "O")
                .add("/proj/AGENTS.md", "A")
                .add("/proj/CLAUDE.md", "C");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertEquals(1, files.files().size());
        assertEquals("AGENTS.override.md", files.files().get(0).name());
        assertEquals("O", files.files().get(0).content());
    }

    @Test
    @DisplayName("候选名优先级：没有 override 时 AGENTS.md 赢过 CLAUDE.md")
    void candidatePriorityFallsBackToAgentsMd() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "A")
                .add("/proj/CLAUDE.md", "C");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertEquals(1, files.files().size());
        assertEquals("AGENTS.md", files.files().get(0).name());
    }

    @Test
    @DisplayName("扫描顺序：远的在前、近的在后（更具体的在后生效）")
    void walksUpwardsAndKeepsTheOuterOneFirst() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/sub/AGENTS.md", "近")
                .add("/proj/AGENTS.md", "远");

        ContextFiles files = ContextFiles.discover("/proj/sub",
                new ContextFiles.Options().maxDepth(-1), reader);

        assertEquals(2, files.files().size());
        assertEquals("远", files.files().get(0).content(), "外层的更一般，排前面");
        assertEquals("近", files.files().get(1).content(), "越靠近工作目录的越具体，排后面");
    }

    @Test
    @DisplayName("缺省不向上递归：只看起始目录本身")
    void doesNotWalkUpwardsByDefault() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/sub/AGENTS.md", "近")
                .add("/proj/AGENTS.md", "远");

        ContextFiles files = ContextFiles.discover("/proj/sub", reader);

        assertEquals(1, files.files().size());
        assertEquals("近", files.files().get(0).content());
    }

    @Test
    @DisplayName("同目录 override：一个目录只取优先级最高的候选名")
    void sameDirectoryOverride() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "A")
                .add("/proj/CLAUDE.md", "C");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertEquals(1, files.files().size(), "同目录只留一个");
    }

    @Test
    @DisplayName("真实路径去重：同一个文件不重复进上下文")
    void deduplicatesByRealPath() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/sub/AGENTS.md", "同一份")
                .add("/proj/AGENTS.md", "同一份");
        reader.realPaths.put("/proj/sub/AGENTS.md", "/real/AGENTS.md");
        reader.realPaths.put("/proj/AGENTS.md", "/real/AGENTS.md");

        ContextFiles files = ContextFiles.discover("/proj/sub",
                new ContextFiles.Options().maxDepth(-1), reader);

        assertEquals(1, files.files().size());
        assertTrue(files.warnings().stream().anyMatch(w -> w.contains("重复")), files.warnings().toString());
    }

    @Test
    @DisplayName("全局目录排在最前（更一般的约定先生效）")
    void globalDirsComeFirst() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/home/u/.mos/AGENTS.md", "全局")
                .add("/proj/AGENTS.md", "项目");

        ContextFiles files = ContextFiles.discover("/proj",
                new ContextFiles.Options().globalDirs(List.of("/home/u/.mos")), reader);

        assertEquals(2, files.files().size());
        assertEquals("全局", files.files().get(0).content());
        assertEquals("项目", files.files().get(1).content());
    }

    @Test
    @DisplayName("字节预算：超预算被裁剪，并写进 warnings（不悄悄丢内容）")
    void byteBudgetTrimsAndWarns() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "0123456789\n" + "x".repeat(100));

        ContextFiles files = ContextFiles.discover("/proj",
                new ContextFiles.Options().byteBudget(budgetFor("/proj/AGENTS.md", 76)), reader);

        assertEquals(1, files.files().size());
        assertTrue(files.files().get(0).trimmed());
        assertTrue(files.files().get(0).content().startsWith("0123456789"), files.files().get(0).content());
        assertTrue(files.warnings().stream().anyMatch(w -> w.contains("裁剪")), files.warnings().toString());
    }

    @Test
    @DisplayName("字节预算用完：更具体的文件不再加载，并写进 warnings")
    void budgetExhaustionSkipsTheRestAndWarns() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "0123456789");

        ContextFiles files = ContextFiles.discover("/proj",
                new ContextFiles.Options().byteBudget(1), reader);

        assertTrue(files.files().isEmpty(), "装不下就不算加载到了");
        assertTrue(files.warnings().stream().anyMatch(w -> w.contains("预算")), files.warnings().toString());
        assertEquals("", files.render(), "一个都没装下就不留空标题");
    }

    @Test
    @DisplayName("读取失败：记 warning 并跳过，不中断整体发现")
    void readFailureIsDegraded() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "A");
        reader.failures.add("/proj/AGENTS.md");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertTrue(files.files().isEmpty());
        assertTrue(files.warnings().stream().anyMatch(w -> w.contains("读取失败")), files.warnings().toString());
    }

    @Test
    @DisplayName("render 包装成 project_instructions 块（模型知道每条约定来自哪个文件）")
    void renderWrapsIntoProjectInstructions() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "规则一");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertTrue(files.render().contains("规则一"), files.render());
        assertTrue(files.render().contains("/proj/AGENTS.md"), files.render());
        assertTrue(files.render().contains("<project_instructions path=\"/proj/AGENTS.md\">"),
                files.render());
        assertTrue(files.render().contains("</project_instructions>"), files.render());
    }

    @Test
    @DisplayName("BOM 被去掉（带 BOM 的正文会在第一行留下乱码字符）")
    void stripsBom() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "\uFEFF规则");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertEquals("规则", files.files().get(0).content());
    }

    @Test
    @DisplayName("字节预算裁剪不切开 UTF-8 字符（emoji 不变乱码）")
    void byteBudgetTrimsOnCharacterBoundary() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "😀😀😀😀");

        ContextFiles files = ContextFiles.discover("/proj",
                new ContextFiles.Options().byteBudget(budgetFor("/proj/AGENTS.md", 8)), reader);

        assertEquals(1, files.files().size());
        String content = files.files().get(0).content();
        assertFalse(content.contains("�"), "切出半个字符: " + content);
        assertFalse(hasUnpairedSurrogate(content), "代理对被切开了: " + content);
    }

    @Test
    @DisplayName("裁剪优先落在行边界（不留半行尾巴）")
    void byteBudgetPrefersLineBoundaries() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "第一行\n第二行\n第三行\n");

        ContextFiles files = ContextFiles.discover("/proj",
                new ContextFiles.Options().byteBudget(budgetFor("/proj/AGENTS.md", 16)), reader);

        String content = files.files().get(0).content();
        assertEquals("第一行\n", content, "应该裁在行边界");
    }

    /**
     * 算一个「正文里只剩 {@code remaining} 字节可用」的预算。
     *
     * <p>预算里含包装开销（标题 + 包裹标签 + 截断说明预留）——
     * 这些常量与 Go 参考版同款，写在这里是为了让测试能算出精确的可用字节数，
     * 而不是「随便给个数，裁到哪儿算哪儿」。
     */
    private static int budgetFor(String path, int remaining) {
        String open = "<project_instructions path=\"" + path + "\">\n";
        int header = "项目约定与指引（来自以下上下文文件）：".length() + 2;
        int overhead = open.length() + "</project_instructions>".length() + 128;
        return header + overhead + 2 + remaining;
    }

    @Test
    @DisplayName("目录不存在：直接失败（宿主配置错了，不是可降级的故障）")
    void missingDirectoryFailsClosed() {
        MapFileReader reader = new MapFileReader();
        reader.directories.add("/proj");

        IOException error = assertThrows(IOException.class,
                () -> ContextFiles.discover("/nowhere", reader));
        assertTrue(error.getMessage().contains("/nowhere"), error.getMessage());
    }

    @Test
    @DisplayName("路径拼接由宿主决定（虚拟文件系统可以不用 /）")
    void pathJoiningIsTheHostsBusiness() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("proj|AGENTS.md", "A");
        reader.separator = "|";

        ContextFiles files = ContextFiles.discover("proj", reader);

        assertEquals(1, files.files().size(), files.warnings().toString());
        assertEquals("A", files.files().get(0).content());
    }

    /** 字符串里是否有落单的代理项。 */
    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 内存文件系统：路径 → 内容；父目录就是去掉最后一段。
     */
    private static final class MapFileReader implements ContextFileReader {
        private final Map<String, byte[]> files = new HashMap<>();
        private final Map<String, String> realPaths = new HashMap<>();
        private final List<String> failures = new java.util.ArrayList<>();
        private final List<String> directories = new java.util.ArrayList<>();
        private String separator = "/";

        MapFileReader add(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public byte[] readIfExists(String path) throws IOException {
            if (failures.contains(path)) {
                throw new IOException("模拟读取失败");
            }
            return files.get(path);
        }

        @Override
        public boolean isDirectory(String path) {
            // 只有显式登记过的目录才算目录（模拟真实文件系统）
            return directories.isEmpty() || directories.contains(path);
        }

        @Override
        public String realPath(String path) {
            return realPaths.getOrDefault(path, path);
        }

        @Override
        public String parentOf(String dir) {
            int idx = dir.lastIndexOf('/');
            if (idx <= 0) {
                return null;
            }
            return dir.substring(0, idx);
        }

        @Override
        public String join(String dir, String name) {
            // 路径拼接是宿主的事：这里可以换成虚拟文件系统的分隔符
            return dir.endsWith(separator) ? dir + name : dir + separator + name;
        }
    }
}
