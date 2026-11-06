package net.hegq.mos4j.tool.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 默认的技能目录读取实现（直接读本地文件）。
 *
 * <p>宿主不注入 {@link SkillReader} 时用它——技能目录是宿主显式声明的，
 * 框架不再替它做边界判断（越界判断只能由宿主的事）。
 */
public final class OsSkillReader implements SkillReader {

    /** 构造器。 */
    public OsSkillReader() {
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    @Override
    public List<Entry> readDir(String path) throws IOException {
        try (Stream<Path> children = Files.list(Path.of(path))) {
            return children.map(child -> new Entry(child.getFileName().toString(),
                    Files.isDirectory(child))).toList();
        }
    }
}
