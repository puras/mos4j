package net.hegq.mos4j.context;

import net.hegq.mos4j.fs.SandboxedFs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 截断落盘逃生通道：太长又不能丢的内容，写到宿主指定的目录，让模型自己按需去读。
 *
 * <p><b>为什么需要它</b>：工具输出可能有几百 KB。直接截断 → 模型看不到关键信息；
 * 直接塞进上下文 → 预算爆掉。落盘是第三条路：上下文里只留「完整内容在
 * {@code spill/xxx.txt}，需要就读」，模型可以自己挑要读的部分。
 *
 * <p><b>目录必须在文件 jail 内</b>：落盘目录由宿主指定，但**必须落在 jail 的可读根里**——
 * 否则就多了一条绕过 jail 的旁路（模型能读到 jail 外的文件内容）。
 * 越界直接报错（fail-closed），不是「默默挪到别处」。
 *
 * <p><b>落盘失败诚实降级</b>：写失败时内容**原样返回**并附警告，
 * 由上层决定截断——绝不假装落盘成功（模型会去找一个不存在的文件）。
 */
public final class SpillStore {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SandboxedFs fs;
    private final String dir;
    private final AtomicLong counter = new AtomicLong();

    private SpillStore(SandboxedFs fs, String dir) {
        this.fs = fs;
        this.dir = dir;
    }

    /**
     * 构造落盘通道。
     *
     * @param fs      文件 jail
     * @param spillDir 落盘目录（相对 jail 锚点，必须在可读根内）
     * @throws IOException 目录不存在
     * @throws net.hegq.mos4j.fs.PathDeniedError 目录越界（fail-closed）
     */
    public static SpillStore of(SandboxedFs fs, String spillDir) throws IOException {
        if (fs == null) {
            throw new IllegalArgumentException("context: SpillStore 需要文件 jail");
        }
        if (spillDir == null || spillDir.isBlank()) {
            throw new IllegalArgumentException("context: 落盘目录不能为空");
        }
        // 越界在这里就炸：构造期失败比运行期默默写到别处强
        fs.resolveExisting(spillDir);
        return new SpillStore(fs, spillDir);
    }

    /** 落盘目录（相对 jail 锚点）。 */
    public String dir() {
        return dir;
    }

    /**
     * 把内容落盘。
     *
     * @param name    内容名（用于文件名，会做安全化）
     * @param content 内容
     * @return 落盘结果（失败时内容原样返回 + 警告）
     */
    public SpillResult spill(String name, String content) {
        String text = content == null ? "" : content;
        String fileName = fileName(name);
        try {
            Path target = fs.resolveForWrite(dir + "/" + fileName);
            writeAtomically(target, text);
            return SpillResult.ok(dir + "/" + fileName, text);
        } catch (IOException | RuntimeException e) {
            return SpillResult.failed(text, "落盘失败（内容未落盘，已原样返回）: " + e.getMessage());
        }
    }

    /**
     * 原子落盘：同目录临时文件 + rename。
     *
     * <p>不这么做的话，模型可能在写入中途去读那个文件——读到半截内容，
     * 然后把半截当成事实。{@code ATOMIC_MOVE} 不支持时才降级为普通 move。
     */
    private static void writeAtomically(Path target, String text) throws IOException {
        Path dir = target.getParent();
        Path temp = Files.createTempFile(dir, ".spill-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 生成安全的文件名：去掉路径分隔符与控制字符。
     */
    private String fileName(String name) {
        String base = name == null || name.isBlank() ? "output" : name;
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String safe = sb.toString();
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        return "spill-" + STAMP.format(Instant.now()) + "-" + counter.incrementAndGet() + "-" + safe + ".txt";
    }
}
