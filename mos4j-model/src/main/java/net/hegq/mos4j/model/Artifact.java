package net.hegq.mos4j.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * 模型产出的二进制产物（跨模型种类的公共底座）。
 *
 * <p><b>为什么单独一个类型</b>（而不是把字节塞进 {@code Message.content}）：
 * <ul>
 *   <li><b>上下文会爆</b>：音频 / 图片 / 视频的字节进对话历史 = 每一轮回灌都带着整段二进制走。</li>
 *   <li><b>审计记不下</b>：会话日志要能落盘、能被人读；二进制只能记引用（位置 + 大小 + 媒体类型）。</li>
 *   <li><b>可能带凭据</b>：产物来自远端服务，混进消息就跟着 prompt 走（prompt 会被送去别的厂商）。</li>
 * </ul>
 *
 * <p><b>两种形态，用类型区分</b>（而不是用空值区分——「内容为空」与「内容在别处」是两件事，
 * 混成一个可空字段就会长出「既没有内容也没有位置」的半成品状态）：
 * <ul>
 *   <li>{@link InMemory} —— 内容还在内存里（宿主还没落盘）；</li>
 *   <li>{@link OnDisk} —— 内容已由宿主落盘，这里只带引用。</li>
 * </ul>
 *
 * <p><b>模型侧只回引用，怎么用由宿主定</b>：产物自己不写文件（写盘是宿主的权限边界；
 * mos4j 的写前观察门与沙箱在更上层），这里只描述「这是什么、多大、在哪」。
 *
 * <p><b>为什么不用 record 装字节</b>：record 的 equals/hashCode 对数组走引用比较
 * （内容相同但数组不同的两个产物不相等），逐字节比较又太贵。所以这里用 sealed 接口 +
 * 两个 record，并把 {@link InMemory} 的相等性改成按内容比较（只在真比较时才付这个成本）。
 */
public sealed interface Artifact permits Artifact.InMemory, Artifact.OnDisk {

    /** 媒体类型（如 {@code audio/mpeg}、{@code image/png}、{@code application/pdf}）。 */
    String mediaType();

    /** 展示名（可空 = 没有名字；宿主拼文件名时用）。 */
    String fileName();

    /** 字节数。 */
    long size();

    /**
     * 内存中的产物（内容还没落盘）。
     *
     * @param content   内容字节（构造时防御性复制）
     * @param mediaType 媒体类型
     * @param fileName  展示名（可空）
     */
    record InMemory(byte[] content, String mediaType, String fileName) implements Artifact {

        /**
         * @param content   内容字节（可空 = 无内容，非法）
         * @param mediaType 媒体类型（可空 / 空白 = 非法）
         * @param fileName  展示名（空白按无名字处理）
         */
        public InMemory {
            if (content == null) {
                throw new IllegalArgumentException("llm: 内存产物没有内容");
            }
            content = content.clone();
            mediaType = requireMediaType(mediaType);
            fileName = blankToNull(fileName);
        }

        /** 内容字节。 */
        @Override
        public long size() {
            return content.length;
        }

        /**
         * 相等性按<b>内容</b>判定（两个内容相同的内存产物是同一个产物）。
         *
         * <p>为什么不按引用：产物常常被来回传递（模型 → 工具 → 宿主 → 日志），
         * 按引用判等会把「同一个东西」说成「两个东西」。
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InMemory that)) {
                return false;
            }
            return Arrays.equals(content, that.content)
                    && mediaType.equals(that.mediaType)
                    && Objects.equals(fileName, that.fileName);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Arrays.hashCode(content) + mediaType.hashCode())
                    + Objects.hashCode(fileName);
        }
    }

    /**
     * 已落盘的产物（只带引用，不带字节）。
     *
     * @param location  位置（宿主可读；可空 = 无位置，非法）
     * @param mediaType 媒体类型
     * @param size      字节数
     * @param fileName  展示名（可空）
     */
    record OnDisk(String location, String mediaType, long size, String fileName) implements Artifact {

        /**
         * @param location  位置（可空 / 空白 = 非法）
         * @param mediaType 媒体类型（可空 / 空白 = 非法）
         * @param size      字节数（负数 = 非法）
         * @param fileName  展示名（空白按无名字处理）
         */
        public OnDisk {
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("llm: 落盘产物没有位置");
            }
            mediaType = requireMediaType(mediaType);
            if (size < 0) {
                throw new IllegalArgumentException("llm: 落盘产物的字节数不能为负");
            }
            fileName = blankToNull(fileName);
        }
    }

    /**
     * 从真实文件构造引用（只 stat 大小，不读内容）。
     *
     * @param path      文件路径
     * @param mediaType 媒体类型
     * @return 落盘产物引用
     */
    static OnDisk onDisk(Path path, String mediaType) {
        try {
            return new OnDisk(path.toString(), mediaType, Files.size(path), path.getFileName().toString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("llm: 读不到产物文件的大小: " + path, e);
        }
    }

    /** 媒体类型校验（空 = 非法——产物没有类型，宿主就不知道该怎么处理它）。 */
    private static String requireMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("llm: 产物没有媒体类型");
        }
        return mediaType;
    }

    /** 空白按无值处理。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
