package net.hegq.mos4j.core;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 版本号与 Maven 坐标对齐的守护测试。
 *
 * <p>版本号有且只有一个真源：**父 POM 的 {@code <version>}**。
 * {@link Version#VERSION} 是它的对外投影；两边不一致时，
 * 「宿主报的版本」与「实际发布的构件」就对不上——这种错只有发布后才看得见，
 * 所以在这里钉死。
 */
class VersionTest {

    @Test
    void theVersionLooksLikeAVersion() {
        assertTrue(Version.VERSION.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"),
                "版本号格式应为 x.y.z 或 x.y.z-SNAPSHOT，实际: " + Version.VERSION);
    }

    @Test
    void theVersionMatchesTheParentPom() {
        Path pom = parentPom();
        assumeTrue(pom != null, "找不到父 POM（运行目录既不是模块目录也不是仓库根）");

        String declared = readProjectVersion(pom);
        assertEquals(declared, Version.VERSION,
                "Version.VERSION 与父 POM 的 <version> 不一致——版本号的真源是 Maven 坐标，两边都要改");
    }

    @Test
    void theVersionAccessorReturnsTheConstant() {
        assertEquals(Version.VERSION, Version.version());
    }

    private static Path parentPom() {
        for (Path candidate : List.of(Path.of("..", "pom.xml"), Path.of("pom.xml"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String readProjectVersion(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            var doc = factory.newDocumentBuilder().parse(pom.toFile());
            var nodes = doc.getElementsByTagName("version");
            assertTrue(nodes.getLength() > 0, "父 POM 里没有 <version>");
            return nodes.item(0).getTextContent().trim();
        } catch (Exception e) {
            throw new AssertionError("父 POM 解析失败: " + pom, e);
        }
    }
}
