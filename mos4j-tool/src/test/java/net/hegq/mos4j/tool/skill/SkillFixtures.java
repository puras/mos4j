package net.hegq.mos4j.tool.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 技能测试夹具：在模块内 {@code target/test-tmp} 下造真实技能目录。
 *
 * <p><b>为什么不用 {@code @TempDir}</b>：执行沙箱的系统临时目录不可写
 * （{@code mktemp} 报 Operation not permitted），JUnit 的 {@code @TempDir} 走的正是那里。
 * 项目内 {@code target/test-tmp} 可写，且随 {@code clean} 一起消失。
 *
 * <p><b>为什么用真文件</b>：技能发现要的是「目录 + 文件」这套真实拓扑
 * （子目录下钻、{@code SKILL.md} 命名、坏文件跳过）——用内存假 reader 测的是
 * reader 的实现细节，不是发现语义。{@link SkillCatalog.Options} 的 {@code reader}
 * 可空（走本地文件）。
 */
final class SkillFixtures {

    private SkillFixtures() {
    }

    /** 新建一个独立的根目录（每次调用唯一，互不干扰）。 */
    static Path newRoot() throws IOException {
        return Files.createDirectories(
                Path.of("target", "test-tmp", "skill-" + System.nanoTime()).toAbsolutePath());
    }

    /** 在给定目录写一个技能文件（{@code SKILL.md}），返回该目录。 */
    static Path write(Path dir, String frontmatter, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n" + frontmatter + "\n---\n" + body, StandardCharsets.UTF_8);
        return dir;
    }

    /** 在父目录下写一个「名字与目录名相同」的规范技能，返回该技能目录。 */
    static Path writeSkill(Path parent, String name, String description, String body) throws IOException {
        return write(parent.resolve(name),
                "name: " + name + "\ndescription: " + description, body);
    }

    /** 用根目录装一份技能清单（缺省预算、无探针）。 */
    static SkillCatalog load(Path root) {
        return SkillCatalog.load(SkillCatalog.Options.of(List.of(root.toString())));
    }

    /** 从若干路径装一份技能清单。 */
    static SkillCatalog load(String... dirs) {
        return SkillCatalog.load(SkillCatalog.Options.of(List.of(dirs)));
    }

    /** UTF-8 字节数（预算断言用）。 */
    static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}