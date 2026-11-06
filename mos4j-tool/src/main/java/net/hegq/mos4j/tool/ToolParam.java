package net.hegq.mos4j.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数字段的描述与必填声明（Java 侧自创机制，见 {@code docs/contract/SELF-INVENTED.md} SI-4）。
 *
 * <p><b>对应 Go 侧的 {@code mos:"desc=…,required=true"} struct tag</b>。</p>
 *
 * <p><b>必填只由显式声明决定</b>：不按「非指针且无 omitempty 即必填」默认推断——
 * 那会让模型被强制补齐带零值语义的参数（传 {@code 0} 与「不传」语义不同）；
 * 而 {@code required=true} 反而不必填，与 tag 语义相悖。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface ToolParam {

    /** 字段说明（进 schema 的 description）。 */
    String desc() default "";

    /** 是否必填（默认否）。 */
    boolean required() default false;
}
