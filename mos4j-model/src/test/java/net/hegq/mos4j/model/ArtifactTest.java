package net.hegq.mos4j.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共底座 {@link Artifact} 的守护测试。
 *
 * <p>守护三条：两种形态不混（内容与引用二选一）、字节防御性复制（外部改动不污染产物）、
 * 缺媒体类型 / 负大小一律拒绝（fail-closed）。
 */
class ArtifactTest {

    @Test
    void inMemoryKeepsContentAndSize() {
        Artifact.InMemory artifact = new Artifact.InMemory(
                "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), "audio/mpeg", "clip.mp3");

        assertEquals("audio/mpeg", artifact.mediaType());
        assertEquals("clip.mp3", artifact.fileName());
        assertEquals(3L, artifact.size());
        assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), artifact.content());
    }

    @Test
    void inMemoryCopiesContentDefensively() {
        byte[] source = {1, 2, 3};
        Artifact.InMemory artifact = new Artifact.InMemory(source, "application/octet-stream", null);

        source[0] = 9;

        assertEquals(1, artifact.content()[0], "外部改原数组不应污染产物");
    }

    @Test
    void inMemoryEqualityIsByContent() {
        Artifact a = new Artifact.InMemory(new byte[]{1, 2}, "image/png", "x.png");
        Artifact b = new Artifact.InMemory(new byte[]{1, 2}, "image/png", "x.png");
        Artifact c = new Artifact.InMemory(new byte[]{1, 3}, "image/png", "x.png");

        assertEquals(a, b, "内容相同即为同一产物");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c, "内容不同就不是同一产物");
    }

    @Test
    void onDiskKeepsReferenceWithoutBytes() {
        Artifact.OnDisk artifact = new Artifact.OnDisk("/work/out/clip.mp3", "audio/mpeg", 2048, "clip.mp3");

        assertEquals("/work/out/clip.mp3", artifact.location());
        assertEquals("audio/mpeg", artifact.mediaType());
        assertEquals(2048L, artifact.size());
    }

    @Test
    void onDiskFromPathReadsSize() throws Exception {
        // 不用 @TempDir：沙箱下系统临时目录不可创建，落在工程内的 target/ 下。
        Path dir = Path.of("target", "artifact-test");
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "clip-", ".mp3");
        Files.write(file, new byte[]{1, 2, 3, 4});
        try {
            Artifact.OnDisk artifact = Artifact.onDisk(file, "audio/mpeg");

            assertEquals(4L, artifact.size());
            assertEquals(file.toString(), artifact.location());
            assertEquals(file.getFileName().toString(), artifact.fileName());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void blankMediaTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact.InMemory(new byte[]{1}, " ", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact.OnDisk("/work/x", null, 1, null));
    }

    @Test
    void invalidShapeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact.InMemory(null, "audio/mpeg", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact.OnDisk("  ", "audio/mpeg", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact.OnDisk("/work/x", "audio/mpeg", -1, null));
    }

    @Test
    void blankFileNameBecomesNull() {
        Artifact artifact = new Artifact.InMemory(new byte[]{1}, "audio/mpeg", "   ");

        assertNull(artifact.fileName());
    }

    @Test
    void sizeOfInMemoryFollowsContent() {
        Artifact artifact = new Artifact.InMemory(new byte[7], "application/octet-stream", null);

        assertEquals(7L, artifact.size());
        assertTrue(artifact.size() > 0);
    }
}
