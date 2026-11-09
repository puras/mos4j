package net.hegq.mos4j.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 消息中的一个内容块（多模态：文本 / 图片 / 音频 / 文件）。
 *
 * <p>与 {@link Message#content()} 的关系：{@code content} 是纯文本快捷方式；
 * {@code contentParts} 非空时适配器<b>优先</b>展开为协议的多模态格式。</p>
 *
 * <p>JSON 字段名照抄 Go（{@code type} 无条件；{@code text}/{@code url}/{@code data}/
 * {@code mime_type} 为空则省略）。</p>
 *
 * @param type     内容块类型：text | image | audio | file
 * @param text     文本内容（type=text）
 * @param url      远程地址（type=image/audio/file）
 * @param data     base64 数据（type=image/audio/file）
 * @param mimeType MIME 类型（如 image/png）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text,
        @JsonProperty("url") String url,
        @JsonProperty("data") String data,
        @JsonProperty("mime_type") String mimeType) {

    /** 文本块。 */
    public static ContentPart text(String text) {
        return new ContentPart("text", text, null, null, null);
    }

    /** 图片块（远程地址）。 */
    public static ContentPart imageUrl(String url) {
        return new ContentPart("image", null, url, null, null);
    }

    /** 图片块（base64 数据 + MIME）。 */
    public static ContentPart imageData(String base64, String mimeType) {
        return new ContentPart("image", null, null, base64, mimeType);
    }
}
