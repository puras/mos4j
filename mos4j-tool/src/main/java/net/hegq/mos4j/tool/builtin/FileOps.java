package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.ObserveGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件工具共用的落盘与文案逻辑。
 *
 * <p><b>文案必须与 {@code docs/contract/API.md} §4 的表逐字一致</b>：
 * 那些句子是给模型看的行动指引，不是给人看的日志。
 */
final class FileOps {

    private FileOps() {
    }

    /**
     * 原子落盘：同目录临时文件 + rename。
     *
     * <p>直接写目标文件的话，进程被杀会留下半截文件——那不是「写失败」，
     * 是「写了一半还装作成功」。
     *
     * @param target  目标路径
     * @param content 内容
     * @throws java.io.IOException 落盘失败
     */
    static void writeAtomically(Path target, String content) throws java.io.IOException {
        Path dir = target.getParent();
        if (dir == null) {
            throw new java.io.IOException("目标路径没有父目录: " + target);
        }
        Path temp = Files.createTempFile(dir, ".mos4j-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * write 的拒绝文案。
     *
     * @param path    路径
     * @param verdict 裁决
     * @return 文案
     */
    static String writeDenial(String path, ObserveGate.Verdict verdict) {
        return switch (verdict.reason()) {
            case NOT_OBSERVED -> "write 拒绝：\"" + path + "\" 已存在但未被读取过。先 read 读取当前内容，再决定是否覆盖";
            case DELETED -> "write 拒绝：\"" + path + "\" 自读取后已被删除。请重新 read 确认后再写入";
            case MODIFIED -> "write 拒绝：\"" + path + "\" 自读取后已被修改。请重新 read 获取最新内容后再写入";
            case CHECK_FAILED -> "write 拒绝：\"" + path + "\" 写前检查失败（" + verdict.detail() + "）";
            default -> "write 拒绝：\"" + path + "\" 未通过写前检查";
        };
    }

    /**
     * edit 的拒绝文案。
     *
     * @param path    路径
     * @param verdict 裁决
     * @return 文案
     */
    static String editDenial(String path, ObserveGate.Verdict verdict) {
        return switch (verdict.reason()) {
            case NOT_OBSERVED -> "edit 拒绝：编辑前必须先 read 读取 " + path + " 的当前内容";
            case DELETED -> "edit 拒绝：编辑前必须先 read 读取 " + path + " 的当前内容（该文件自读取后已被删除）";
            case MODIFIED -> "edit 拒绝：" + path + " 自读取后已被修改。请重新 read 获取最新内容后再编辑";
            case CHECK_FAILED -> "edit 拒绝：" + path + " 写前检查失败（" + verdict.detail() + "）";
            default -> "edit 拒绝：" + path + " 未通过写前检查";
        };
    }
}
