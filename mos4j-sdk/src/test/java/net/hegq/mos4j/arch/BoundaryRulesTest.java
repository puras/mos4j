package net.hegq.mos4j.arch;

import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Projection;
import net.hegq.mos4j.session.ToolResult;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块边界检查（T-INF.3 / CI 门 C3）。
 *
 * <p><b>为什么需要这道门</b>：依赖纪律靠人守不住。一次「顺手」的 import 就能把
 * 分层搅乱——最典型的两种：
 * <ol>
 *   <li><b>反向依赖</b>：能力层（工具、会话、日志）import 驱动层或装配层，
 *       于是底层能力被上层拖住，没法单独复用，也让「换驱动」变成改底层的事；</li>
 *   <li><b>日志与协议分家</b>：日志侧另抄一套 Message/ToolCall DTO，
 *       于是「同一件事在两处有两个形状」，转换点一多，字段名冻结的守护就漏了。</li>
 * </ol>
 *
 * <p>本测试不引入 ArchUnit（依赖纪律：运行时依赖只有 Jackson / RE2J / JDK），
 * 用 JDK 自带的文件扫描与 XML 解析实现同等门禁。
 */
class BoundaryRulesTest {

    /** 能力层：可独立复用的基础能力，不得知道驱动层与装配层。 */
    private static final List<String> CAPABILITY_MODULES = List.of(
            "mos4j-core", "mos4j-llm", "mos4j-tool", "mos4j-session", "mos4j-hook",
            "mos4j-execpath", "mos4j-fs", "mos4j-sandbox", "mos4j-policy", "mos4j-context");

    /** 驱动层：回合循环，可以用能力层，不得用装配层。 */
    private static final String DRIVER_MODULE = "mos4j-driver";

    /** 装配层：门面与 CLI，可以用一切。 */
    private static final String ASSEMBLY_MODULE = "mos4j-sdk";

    /** 装配层的包前缀（门面 + CLI）。 */
    private static final List<String> ASSEMBLY_PACKAGES = List.of(
            "net.hegq.mos4j.sdk.", "net.hegq.mos4j.cli.");

    /** 驱动层的包前缀。 */
    private static final List<String> DRIVER_PACKAGES = List.of(
            "net.hegq.mos4j.driver.");

    /** 共享值类型：日志与协议必须是同一批类（各自一份 DTO 就是分家）。 */
    private static final List<String> SHARED_VALUE_TYPES = List.of(
            "net.hegq.mos4j.llm.model.Message",
            "net.hegq.mos4j.llm.model.Role",
            "net.hegq.mos4j.llm.model.ToolCall",
            "net.hegq.mos4j.llm.model.Usage",
            "net.hegq.mos4j.llm.model.InputTokenDetails",
            "net.hegq.mos4j.llm.model.OutputTokenDetails");

    @Test
    void capabilityModulesDoNotImportDriverOrAssembly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : CAPABILITY_MODULES) {
            for (Path file : javaSources(module)) {
                for (String imported : importsOf(file)) {
                    if (isIn(imported, DRIVER_PACKAGES) || isIn(imported, ASSEMBLY_PACKAGES)) {
                        violations.add(module + " → " + imported + "（" + root().relativize(file) + "）");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "能力层不得 import 驱动层/装配层（反向依赖会让底层能力被上层拖住）：\n"
                        + String.join("\n", violations));
    }

    @Test
    void driverModuleDoesNotImportAssembly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources(DRIVER_MODULE)) {
            for (String imported : importsOf(file)) {
                if (isIn(imported, ASSEMBLY_PACKAGES)) {
                    violations.add(DRIVER_MODULE + " → " + imported
                            + "（" + root().relativize(file) + "）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "驱动层不得 import 装配层（门面是可选外壳，驱动不能反过来依赖它）：\n"
                        + String.join("\n", violations));
    }

    @Test
    void moduleDependenciesFollowTheLayering() throws Exception {
        Map<String, Set<String>> dependencies = moduleDependencies();
        List<String> violations = new ArrayList<>();
        List<String> testScoped = new ArrayList<>();
        for (String module : CAPABILITY_MODULES) {
            for (String dependency : dependencies.getOrDefault(module, Set.of())) {
                if (dependency.startsWith("test:")) {
                    testScoped.add(module + "（test 级）→ " + dependency.substring("test:".length()));
                    continue;
                }
                if (DRIVER_MODULE.equals(dependency) || ASSEMBLY_MODULE.equals(dependency)) {
                    violations.add(module + " 依赖 " + dependency);
                }
            }
        }
        for (String dependency : dependencies.getOrDefault(DRIVER_MODULE, Set.of())) {
            if (dependency.startsWith("test:")) {
                testScoped.add(DRIVER_MODULE + "（test 级）→ " + dependency.substring("test:".length()));
                continue;
            }
            if (ASSEMBLY_MODULE.equals(dependency)) {
                violations.add(DRIVER_MODULE + " 依赖 " + ASSEMBLY_MODULE);
            }
        }
        assertTrue(violations.isEmpty(),
                "模块依赖方向必须与分层一致（源码里没 import 也能靠 pom 引入）：\n"
                        + String.join("\n", violations)
                        + (testScoped.isEmpty() ? "" : "\n（test 级依赖，仅记录：" + testScoped + "）"));
    }

    @Test
    void protocolLayerDoesNotDependOnTheLogLayer() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources("mos4j-llm")) {
            for (String imported : importsOf(file)) {
                if (imported.startsWith("net.hegq.mos4j.session.")) {
                    violations.add("mos4j-llm → " + imported
                            + "（" + root().relativize(file) + "）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "协议层不得依赖日志层：日志是协议的投影，方向反了就会出现两套 DTO：\n"
                        + String.join("\n", violations));
    }

    @Test
    void sharedValueTypesHaveExactlyOneDefinition() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String type : SHARED_VALUE_TYPES) {
            List<String> owners = new ArrayList<>();
            for (String module : allModules()) {
                Path expected = root().resolve(module).resolve("src/main/java")
                        .resolve(type.replace('.', '/') + ".java");
                if (Files.exists(expected)) {
                    owners.add(module);
                }
            }
            if (owners.size() != 1) {
                violations.add(type + " 的定义出现在 " + owners
                        + "（必须恰好一处：日志与协议共用同一批类）");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void projectionUsesTheProtocolValueTypes() {
        // 投影产出的消息就是协议层的 Message 类（不是日志侧另抄的一份）。
        Event user = Event.builder(EventKind.USER).text("你好").build();
        Event assistant = Event.builder(EventKind.ASSISTANT)
                .text("调工具")
                .toolCalls(List.of(new ToolCall("call_1", "read", "{}")))
                .build();
        Event result = Event.builder(EventKind.TOOL_RESULT)
                .toolResult(new ToolResult("call_1", "内容", false))
                .build();

        List<Message> messages = Projection.project(List.of(user, assistant, result));

        assertFalse(messages.isEmpty());
        for (Message message : messages) {
            assertSame(Message.class, message.getClass(),
                    "投影必须产出协议层的 Message 类（日志与协议同形）");
        }
        // 工具调用同样是协议层的 ToolCall（不是日志侧副本）。
        Message assistantMessage = messages.stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .findFirst().orElseThrow();
        assertSame(ToolCall.class, assistantMessage.toolCalls().get(0).getClass(),
                "工具调用必须是协议层的 ToolCall 类");
    }

    @Test
    void coreVocabularyLivesInCoreOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : allModules()) {
            if ("mos4j-core".equals(module)) {
                continue;
            }
            Path corePackage = root().resolve(module).resolve("src/main/java/net/hegq/mos4j/core");
            if (!Files.isDirectory(corePackage)) {
                continue;
            }
            try (Stream<Path> files = Files.list(corePackage)) {
                List<Path> list = files.filter(p -> p.toString().endsWith(".java")).toList();
                if (!list.isEmpty()) {
                    violations.add(module + " 在 net.hegq.mos4j.core 包里定义了 "
                            + list.stream().map(p -> p.getFileName().toString()).toList()
                            + "（共享词汇表只能有一处，否则各模块各抄一份 = 两份真相）");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    // ---- 扫描工具 ----

    private static boolean isIn(String imported, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (imported.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> javaSources(String module) throws IOException {
        Path base = root().resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(base)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        List<String> imports = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            String rest = trimmed.substring("import ".length()).trim();
            if (rest.startsWith("static ")) {
                rest = rest.substring("static ".length()).trim();
            }
            if (!rest.endsWith(";")) {
                continue;
            }
            rest = rest.substring(0, rest.length() - 1).trim();
            int space = rest.indexOf(' ');
            if (space > 0) {
                rest = rest.substring(0, space);
            }
            imports.add(rest);
        }
        return imports;
    }

    /** 解析各模块 pom 里的模块依赖（只看 net.hegq:mos4j-*）。 */
    private static Map<String, Set<String>> moduleDependencies() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String module : allModules()) {
            Path pom = root().resolve(module).resolve("pom.xml");
            if (!Files.exists(pom)) {
                continue;
            }
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            NodeList nodes = document.getElementsByTagName("dependency");
            Set<String> deps = new LinkedHashSet<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element dependency = (Element) nodes.item(i);
                String group = text(dependency, "groupId");
                String artifact = text(dependency, "artifactId");
                String scope = text(dependency, "scope");
                if ("net.hegq".equals(group) && artifact != null && artifact.startsWith("mos4j-")) {
                    // test 级依赖不影响运行时分层，但也要记录（供人工核对）
                    deps.add("test".equals(scope) ? "test:" + artifact : artifact);
                }
            }
            result.put(module, deps);
        }
        return result;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        String value = list.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    /** 工程根目录（含 mos4j-core 与父 pom 的目录）。 */
    private static Path root() {
        String basedir = System.getProperty("basedir");
        Path start = basedir == null ? Path.of("").toAbsolutePath() : Path.of(basedir).toAbsolutePath();
        for (Path candidate = start.normalize(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("mos4j-core")) && Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到工程根目录（basedir=" + start + "）");
    }

    /** 全部模块目录（mos4j-*，排除 target）。 */
    private static List<String> allModules() throws IOException {
        try (Stream<Path> dirs = Files.list(root())) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("mos4j-"))
                    .sorted()
                    .toList();
        }
    }
}
