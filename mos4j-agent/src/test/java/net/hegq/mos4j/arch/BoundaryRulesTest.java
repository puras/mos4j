package net.hegq.mos4j.arch;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
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
 * 模块与包边界检查（T-INF.3 / CI 门 C3）。
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
 * <p><b>2026-11-08 的变化</b>：模块从十二个合并成六个，边界门随之从
 * <b>「模块级」改成「包前缀级」</b>——合并后同层的能力住进了同一个 Maven 模块，
 * 只看 pom 已经分不出「工具层」和「编排层」了，于是把门下沉到包：
 * 能力层的包一律不许 import 智能体层（agent / driver / sdk / cli）的包。
 * 模块级的依赖方向门仍然保留（六个模块之间的方向仍然要守住），
 * 另加一道「默认 reactor 恰为这六个模块」的门，防止模块清单被悄悄加回去。
 *
 * <p>本测试不引入 ArchUnit（依赖纪律：运行时依赖只有 Jackson / RE2J / JDK），
 * 用 JDK 自带的文件扫描与 XML 解析实现同等门禁。
 */
class BoundaryRulesTest {

    /**
     * 能力层包：可独立复用的基础能力，不得知道智能体层。
     * 前缀匹配（含子包），与模块合并前的能力层模块一一对应。
     */
    private static final List<String> CAPABILITY_PACKAGES = List.of(
            "net.hegq.mos4j.core", "net.hegq.mos4j.model", "net.hegq.mos4j.fs",
            "net.hegq.mos4j.sandbox", "net.hegq.mos4j.tool", "net.hegq.mos4j.hook",
            "net.hegq.mos4j.policy", "net.hegq.mos4j.mcp", "net.hegq.mos4j.session",
            "net.hegq.mos4j.context", "net.hegq.mos4j.execpath");

    /** 智能体层包：编排与装配（回合驱动、Agent/Config、门面、CLI）。 */
    private static final List<String> AGENT_PACKAGES = List.of(
            "net.hegq.mos4j.agent", "net.hegq.mos4j.driver",
            "net.hegq.mos4j.sdk", "net.hegq.mos4j.cli");

    /** 日志层包：协议的投影，只能被智能体层读，不能反向进协议层。 */
    private static final String LOG_PACKAGE = "net.hegq.mos4j.session";

    /** 协议层包：ChatModel 家族与共享值类型。 */
    private static final String PROTOCOL_PACKAGE = "net.hegq.mos4j.model";

    /** 默认 reactor 的六个 SDK 模块（顺序即依赖方向）。 */
    private static final List<String> SDK_MODULES = List.of(
            "mos4j-core", "mos4j-model", "mos4j-runtime",
            "mos4j-tool", "mos4j-session", "mos4j-agent");

    /** 模块之间允许的依赖方向（下层不得知道上层）。 */
    private static final Map<String, Set<String>> ALLOWED_MODULE_DEPS = Map.of(
            "mos4j-core", Set.of(),
            "mos4j-model", Set.of("mos4j-core"),
            "mos4j-runtime", Set.of(),
            "mos4j-tool", Set.of("mos4j-core", "mos4j-model", "mos4j-runtime"),
            "mos4j-session", Set.of("mos4j-core", "mos4j-model", "mos4j-runtime"),
            "mos4j-agent", Set.of("mos4j-core", "mos4j-model", "mos4j-runtime",
                    "mos4j-tool", "mos4j-session"));

    /** 共享值类型：日志与协议必须是同一批类（各自一份 DTO 就是分家）。 */
    private static final List<String> SHARED_VALUE_TYPES = List.of(
            "net.hegq.mos4j.model.Message",
            "net.hegq.mos4j.model.Role",
            "net.hegq.mos4j.model.ToolCall",
            "net.hegq.mos4j.model.Usage",
            "net.hegq.mos4j.model.InputTokenDetails",
            "net.hegq.mos4j.model.OutputTokenDetails");

    @Test
    void capabilityPackagesDoNotImportTheAgentLayer() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : allMainSources()) {
            String pkg = packageOf(file);
            if (!isIn(pkg, CAPABILITY_PACKAGES)) {
                continue;
            }
            for (String imported : importsOf(file)) {
                if (isIn(imported, AGENT_PACKAGES)) {
                    violations.add(pkg + " → " + imported
                            + "（" + root().relativize(file) + "）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "能力层的包不得 import 智能体层（agent/driver/sdk/cli）"
                        + "——反向依赖会让底层能力被上层拖住：\n"
                        + String.join("\n", violations));
    }

    @Test
    void moduleDependenciesFollowTheLayering() throws Exception {
        Map<String, Set<String>> dependencies = moduleDependencies();
        List<String> violations = new ArrayList<>();
        List<String> testScoped = new ArrayList<>();
        for (String module : SDK_MODULES) {
            Set<String> allowed = ALLOWED_MODULE_DEPS.getOrDefault(module, Set.of());
            for (String dependency : dependencies.getOrDefault(module, Set.of())) {
                if (dependency.startsWith("test:")) {
                    testScoped.add(module + "（test 级）→ " + dependency.substring("test:".length()));
                    continue;
                }
                if (!allowed.contains(dependency)) {
                    violations.add(module + " 依赖 " + dependency + "（允许："
                            + new java.util.TreeSet<>(allowed) + "）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "模块依赖方向必须与分层一致（源码里没 import 也能靠 pom 引入）：\n"
                        + String.join("\n", violations)
                        + (testScoped.isEmpty() ? "" : "\n（test 级依赖，仅记录：" + testScoped + "）"));
    }

    @Test
    void defaultReactorIsExactlyTheSixSdkModules() throws Exception {
        List<String> declared = rootPomModules();
        assertEquals(SDK_MODULES, declared,
                "默认 reactor 必须恰好是这六个 SDK 模块（合并后的模块清单；"
                        + "按需模块走 profile，不在默认清单里）");
    }

    @Test
    void protocolLayerDoesNotDependOnTheLogLayer() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : allMainSources()) {
            String pkg = packageOf(file);
            if (!pkg.equals(PROTOCOL_PACKAGE) && !pkg.startsWith(PROTOCOL_PACKAGE + ".")) {
                continue;
            }
            for (String imported : importsOf(file)) {
                if (imported.startsWith(LOG_PACKAGE + ".")) {
                    violations.add(pkg + " → " + imported
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

    private static boolean isIn(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.equals(prefix) || value.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 全部模块的 main 源文件（排除 target）。 */
    private static List<Path> allMainSources() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String module : allModules()) {
            result.addAll(javaSources(module));
        }
        return result;
    }

    /** 由源文件内容读出包名（直接读 package 声明，不靠路径猜）。 */
    private static String packageOf(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.substring("package ".length(), trimmed.indexOf(';')).trim();
            }
        }
        return "";
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
            // 取导入名：读到 ';' 或空白为止（容许行尾注释）
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == ';' || Character.isWhitespace(c)) {
                    break;
                }
                name.append(c);
            }
            if (name.length() > 0) {
                imports.add(name.toString());
            }
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

    /** 父 pom 里默认 <modules> 声明的模块（不含 profile 里的按需模块）。 */
    private static List<String> rootPomModules() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(root().resolve("pom.xml").toFile());
        Element rootProject = (Element) document.getElementsByTagName("project").item(0);
        List<String> result = new ArrayList<>();
        // 只看根 <project> 的**直接子** <modules>——profile 里的 <modules> 是按需模块，不算默认清单
        org.w3c.dom.NodeList children = rootProject.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE
                    || !"modules".equals(node.getNodeName())) {
                continue;
            }
            NodeList modules = ((Element) node).getElementsByTagName("module");
            for (int j = 0; j < modules.getLength(); j++) {
                String name = modules.item(j).getTextContent().trim();
                if (name.startsWith("mos4j-")) {
                    result.add(name);
                }
            }
            break;
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
