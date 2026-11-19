package net.hegq.mos4j.examples.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置常量的用例：取值顺序（常量 → 环境变量 → 协议缺省）、密钥不外泄、未知协议 fail-closed。
 *
 * <p><b>用例不假设常量的当前值</b>：`OPENAI_API_KEY` 这类常量是宿主填的（本地填真值、
 * 仓库里应该留空），所以三档顺序测在 {@code resolve(...)} 这个纯函数上——宿主改配置不该
 * 让用例变红。环境变量来源也是注入的，用例不该改真环境。
 */
class ModelConfigTest {

    private static final Map<String, String> NO_ENV = Map.of();

    @Test
    @DisplayName("取值顺序：常量非空就压过环境变量与缺省")
    void constantWinsOverEnvAndDefault() {
        ModelConfig.Provider provider = ModelConfig.resolve("openai",
                "sk-constant", "http://constant/v1", "model-constant",
                "model-default", "ENV_KEY", "ENV_BASE", "ENV_MODEL",
                Map.of("ENV_KEY", "sk-env", "ENV_BASE", "http://env/v1",
                        "ENV_MODEL", "model-env")::get);

        assertEquals("sk-constant", provider.apiKey());
        assertEquals("http://constant/v1", provider.baseUrl());
        assertEquals("model-constant", provider.model());
    }

    @Test
    @DisplayName("常量留空 → 用环境变量（这是推荐姿势：密钥不进源码）")
    void envUsedWhenConstantBlank() {
        ModelConfig.Provider provider = ModelConfig.resolve("openai",
                "", "  ", null,
                "model-default", "ENV_KEY", "ENV_BASE", "ENV_MODEL",
                Map.of("ENV_KEY", "sk-env", "ENV_BASE", "http://env/v1",
                        "ENV_MODEL", "model-env")::get);

        assertEquals("sk-env", provider.apiKey(), "空白常量不该挡住环境变量");
        assertEquals("http://env/v1", provider.baseUrl());
        assertEquals("model-env", provider.model());
    }

    @Test
    @DisplayName("常量与环境变量都没给 → 只剩缺省；密钥与基址留空是合法的（无鉴权端点）")
    void fallsBackToDefault() {
        ModelConfig.Provider provider = ModelConfig.resolve("openai",
                "", "", "", "model-default", "ENV_KEY", "ENV_BASE", "ENV_MODEL",
                NO_ENV::get);

        assertEquals("model-default", provider.model());
        assertTrue(provider.apiKey().isEmpty(), "没密钥不是错误");
        assertTrue(provider.baseUrl().isEmpty(), "基址留空 = 让协议用它的缺省地址");
    }

    @Test
    @DisplayName("三个方言各读各的环境变量名（不串台，也不读别家的）")
    void eachProtocolReadsItsOwnEnvNames() {
        ModelConfig.EnvNames openai = ModelConfig.envNames("openai");
        assertEquals(ModelConfig.ENV_OPENAI_API_KEY, openai.apiKey());
        assertEquals(ModelConfig.ENV_OPENAI_BASE_URL, openai.baseUrl());
        assertEquals(ModelConfig.ENV_OPENAI_MODEL, openai.model());

        ModelConfig.EnvNames anthropic = ModelConfig.envNames("anthropic");
        assertEquals(ModelConfig.ENV_ANTHROPIC_API_KEY, anthropic.apiKey());
        assertEquals(ModelConfig.ENV_ANTHROPIC_MODEL, anthropic.model());

        ModelConfig.EnvNames gemini = ModelConfig.envNames("gemini");
        assertEquals(ModelConfig.ENV_GEMINI_API_KEY, gemini.apiKey());
        assertEquals(ModelConfig.ENV_GEMINI_MODEL, gemini.model());

        assertNotEquals(openai.apiKey(), anthropic.apiKey(), "两个方言不能读同一个变量");
        assertNotEquals(anthropic.apiKey(), gemini.apiKey());
    }

    @Test
    @DisplayName("空白环境变量同于没设：不把空白当配置值")
    void blankEnvIsTreatedAsUnset() {
        ModelConfig.Provider provider = ModelConfig.resolve("openai",
                "", "", "", "model-default", "ENV_KEY", "ENV_BASE", "ENV_MODEL",
                Map.of("ENV_KEY", "   ", "ENV_MODEL", "")::get);

        assertTrue(provider.apiKey().isEmpty());
        assertEquals("model-default", provider.model());
    }

    @Test
    @DisplayName("密钥不外泄：toString() 打码，基址为空时明写「协议缺省」")
    void toStringNeverLeaksTheKey() {
        String rendered = new ModelConfig.Provider("openai", "sk-secret-123", null, "gpt-test")
                .toString();

        assertFalse(rendered.contains("sk-secret-123"), "密钥不能出现在 toString 里: " + rendered);
        assertTrue(rendered.contains("***"), "要说清密钥设置了（打码）: " + rendered);
        assertTrue(rendered.contains("协议缺省"), "基址为空要说清是谁的缺省: " + rendered);
        assertTrue(rendered.contains("gpt-test"), "模型名不是秘密，要能看见");
    }

    @Test
    @DisplayName("未知协议 fail-closed：报错列出支持的方言，不静默挑一个")
    void unknownProtocolFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ModelConfig.provider("llama.cpp-自造协议", NO_ENV::get));

        assertTrue(error.getMessage().contains("openai"), error.getMessage());
        assertTrue(error.getMessage().contains("gemini"), error.getMessage());
    }

    @Test
    @DisplayName("装配入口与 ModelConfig 同源：提示词没有第二份字面量，空值不塞进配置")
    void exampleConfigComesFromModelConfig() {
        assertEquals(ModelConfig.PROTOCOL, ModelExample.PROTOCOL);
        assertEquals(ModelConfig.SYSTEM_PROMPT, ModelExample.SYSTEM_PROMPT);

        // envOf 只转交非空值：塞空串进去等于对协议层谎称「有鉴权、值是空」
        Map<String, String> env = ModelExample.envOf("openai");
        env.forEach((key, value) -> assertFalse(value == null || value.isBlank(),
                "配置里不该出现空白值: " + key));
        assertTrue(env.keySet().stream()
                        .allMatch(key -> key.equals(ModelSurface.KEY_API_KEY)
                                || key.equals(ModelSurface.KEY_BASE_URL)
                                || key.equals(ModelSurface.KEY_MODEL)),
                "只认注册表约定的三个键: " + env.keySet());
        assertTrue(env.containsKey(ModelSurface.KEY_MODEL), "模型名永远有值（缺省兜底）");
    }
}
