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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.8 上下文文件发现：候选名优先级、向上递归、同目录 override、真实路径去重、字节预算。
 *
 * <p><b>框架不碰文件系统</b>：这里用一个内存 Map 当文件系统，规则照样可测。
 */
class ContextFilesTest {

    @Test
    @DisplayName("候选名优先级：同目录里 AGENTS.md 赢过 MOS.md")
    void candidatePriorityWins() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "A")
                .add("/proj/MOS.md", "M");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertEquals(1, files.files().size());
        assertEquals("AGENTS.md", files.files().get(0).name());
        assertEquals("A", files.files().get(0).content());
    }

    @Test
    @DisplayName("向上递归：每层一个文件，近的在前")
    void walksUpwards() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/sub/AGENTS.md", "近")
                .add("/proj/AGENTS.md", "远");

        ContextFiles files = ContextFiles.discover("/proj/sub", reader);

        assertEquals(2, files.files().size());
        assertEquals("近", files.files().get(0).content());
        assertEquals("远", files.files().get(1).content());
    }

    @Test
    @DisplayName("同目录 override：一个目录只取优先级最高的候选名")
    void sameDirectoryOverride() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "A")
                .add("/proj/MOS.md", "M");

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

        ContextFiles files = ContextFiles.discover("/proj/sub", reader);

        assertEquals(1, files.files().size());
        assertTrue(files.warnings().stream().anyMatch(w -> w.contains("重复")), files.warnings().toString());
    }

    @Test
    @DisplayName("字节预算：超预算被裁剪，并写进 warnings（不悄悄丢内容）")
    void byteBudgetTrimsAndWarns() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "0123456789");

        ContextFiles files = ContextFiles.discover("/proj", reader, 4);

        assertEquals(1, files.files().size());
        assertTrue(files.files().get(0).trimmed());
        assertEquals("0123", files.files().get(0).content());
        assertFalse(files.warnings().isEmpty(), "裁剪必须留下痕迹");
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
    @DisplayName("render 把找到的文件拼成一段提示词")
    void renderConcatenates() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "规则一");

        ContextFiles files = ContextFiles.discover("/proj", reader);

        assertTrue(files.render().contains("规则一"), files.render());
        assertTrue(files.render().contains("/proj/AGENTS.md"), files.render());
    }

    @Test
    @DisplayName("字节预算裁剪不切开 UTF-8 字符（emoji 不变乱码）")
    void byteBudgetTrimsOnCharacterBoundary() throws IOException {
        MapFileReader reader = new MapFileReader()
                .add("/proj/AGENTS.md", "😀😀😀😀");

        ContextFiles files = ContextFiles.discover("/proj", reader, 5);

        assertEquals(1, files.files().size());
        String content = files.files().get(0).content();
        assertFalse(content.contains("�"), "切出半个字符: " + content);
        assertFalse(hasUnpairedSurrogate(content), "代理对被切开了: " + content);
        assertTrue(content.length() < 4, content);
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
