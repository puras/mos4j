package net.hegq.mos4j.tool.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 直接读本地文件的 {@link SubAgentReader}（宿主不注入时的缺省实现）。
 *
 * <p><b>边界</b>：它读什么完全由宿主给的目录列表决定——框架不替宿主做 jail 校验
 * （宿主显式给了目录 = 授权了这些目录）。
 */
public final class OsSubAgentReader implements SubAgentReader {

    @Override
    public byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    @Override
    public List<Entry> readDir(String path) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.list(Path.of(path))) {
            for (Path child : stream.toList()) {
                entries.add(new Entry(child.getFileName().toString(), Files.isDirectory(child)));
            }
        }
        return entries;
    }
}
