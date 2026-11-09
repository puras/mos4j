package net.hegq.mos4j.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 描述一个可供模型调用的函数（工具）。
 *
 * <p>JSON 字段名照抄 Go（{@code name}/{@code description}/{@code parameters} 均无条件输出）。</p>
 *
 * @param name        工具名（调用时用它匹配注册表）
 * @param description 工具说明（模型据此决定调用与否）
 * @param parameters  JSON Schema 对象（参数契约）
 */
public record Tool(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") Map<String, Object> parameters) {
}
