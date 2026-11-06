package net.hegq.mos4j.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段名守护测试（防漂移体系防线 1 的 Java 端；J0 · T0.8）。
 *
 * <p><b>原理</b>：classpath 上的 {@code /llm/fieldnames.json} 是从 Go 参考仓库
 * 一次性抽取的冻结清单（tools/fieldname-extract/extract.py）。本测试保证三件事：</p>
 * <ol>
 *   <li>清单文件在、可解析、自带的 sha256 摘要与内容一致——防止有人直接改清单
 *       而不重新抽取；</li>
 *   <li>Java 侧常量（{@link FieldNames}）是清单的子集且逐字一致——手改常量即红；</li>
 *   <li>核心事件键一个不少——序列化 DTO（J1 点亮）以常量为准，所以改常量
 *       = 改契约，必须走 PR 评审并同步 docs/contract。</li>
 * </ol>
 *
 * <p><b>故意改错验证</b>（G0 门机械判定项）：把 {@link FieldNames#TOOL_CALLS}
 * 临时改成 "tool_calls" → 本测试红。改动即失败，不是等消费端出事才发现。</p>
 */
class FieldNameContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode frozenList() {
        try (InputStream in = FieldNameContractTest.class.getResourceAsStream(FieldNames.FROZEN_LIST_RESOURCE)) {
            assertNotNull(in, "冻结清单必须在 classpath: " + FieldNames.FROZEN_LIST_RESOURCE
                    + "（T0.4 产出，tools/fieldname-extract/ 生成）");
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new AssertionError("冻结清单读取失败", e);
        }
    }

    @Test
    void frozenListIntegrity() throws Exception {
        JsonNode root = frozenList();
        assertTrue(root.has("fields") && root.get("fields").isArray(), "清单须含 fields 数组");

        String expectedSha = root.get("_names_sha256").asText();
        TreeSet<String> names = new TreeSet<>();
        root.get("fields").forEach(f -> names.add(f.get("name").asText()));
        String actualSha = sha256(String.join("\n", new ArrayList<>(names)));
        assertEquals(expectedSha, actualSha,
                "清单自带 sha256 与内容不符——清单被手改过；字段名变更必须重新抽取 + PR 评审，不许手改清单");
    }

    @Test
    void javaConstantsAreSubsetOfFrozenList() {
        JsonNode root = frozenList();
        TreeSet<String> frozen = new TreeSet<>();
        root.get("fields").forEach(f -> frozen.add(f.get("name").asText()));

        for (String key : FieldNames.coreEventKeys()) {
            assertTrue(frozen.contains(key),
                    () -> "Java 常量 '" + key + "' 不在冻结清单中：改字段名 = 契约变更，须两人评审并同步 docs/contract");
        }
        // Usage 的 snake_case 口径也必须是清单子集
        for (String key : List.of(FieldNames.PROMPT_TOKENS, FieldNames.FRESH_INPUT_TOKENS,
                FieldNames.COMPLETION_TOKENS, FieldNames.TOTAL_TOKENS, FieldNames.DETAILS)) {
            assertTrue(frozen.contains(key), () -> "Usage 常量 '" + key + "' 不在冻结清单中");
        }
    }

    @Test
    void frozenListCountMatchesSelfDescription() {
        JsonNode root = frozenList();
        assertEquals(root.get("_count").asInt(), root.get("fields").size(),
                "清单 _count 与 fields 实际条数不符——清单被手改过");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
