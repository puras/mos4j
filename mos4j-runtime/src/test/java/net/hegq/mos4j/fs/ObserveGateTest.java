package net.hegq.mos4j.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写前观察门测试（T3.10；机制 M7；L3-15 … L3-17）。
 */
class ObserveGateTest {

    private Path dir;
    private Path file;
    private ObserveGate gate;

    @BeforeEach
    void setUp() throws IOException {
        dir = Path.of("target", "test-tmp", "observe-" + System.nanoTime()).toAbsolutePath();
        Files.createDirectories(dir);
        file = dir.resolve("a.txt");
        Files.writeString(file, "原始内容", StandardCharsets.UTF_8);
        gate = new ObserveGate();
    }

    // ---------- 三态 ----------

    @Test
    void l3_15_writeWithoutReadIsDenied() {
        ObserveGate.Verdict verdict = gate.verify(file);

        assertFalse(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.NOT_OBSERVED, verdict.reason());
    }

    @Test
    void l3_16_writeAfterReadWithoutChangeIsAllowed() throws IOException {
        gate.observe(file);

        ObserveGate.Verdict verdict = gate.verify(file);

        assertTrue(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.OK, verdict.reason());
    }

    @Test
    void l3_17_writeAfterReadButChangedIsDenied() throws IOException {
        gate.observe(file);
        Files.writeString(file, "被别的东西改了", StandardCharsets.UTF_8);

        ObserveGate.Verdict verdict = gate.verify(file);

        assertFalse(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.MODIFIED, verdict.reason());
    }

    @Test
    void l3_17_deletedAfterReadIsDenied() throws IOException {
        gate.observe(file);
        Files.delete(file);

        ObserveGate.Verdict verdict = gate.verify(file);

        assertFalse(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.DELETED, verdict.reason());
    }

    // ---------- 新建文件 ----------

    @Test
    void newFileDoesNotRequirePriorRead() {
        Path fresh = dir.resolve("fresh.txt");

        ObserveGate.Verdict verdict = gate.verify(fresh);

        assertTrue(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.OK, verdict.reason());
    }

    @Test
    void fileAppearingAfterAbsentObservationIsDenied() throws IOException {
        Path fresh = dir.resolve("fresh.txt");
        gate.observe(fresh);
        Files.writeString(fresh, "别人建的", StandardCharsets.UTF_8);

        ObserveGate.Verdict verdict = gate.verify(fresh);

        assertFalse(verdict.allowed());
        assertEquals(ObserveGate.Verdict.Reason.NOT_OBSERVED, verdict.reason());
    }

    // ---------- 版本令牌 ----------

    @Test
    void versionTokenUsesDevInoSizeMtimeOnUnix() throws IOException {
        VersionToken token = VersionToken.of(file);

        if (token.unix()) {
            assertTrue(token.value().matches("-?\\d+:-?\\d+:-?\\d+:-?\\d+"), token.value());
            assertTrue(token.strong());
            assertNotEquals(-1L, token.dev());
            assertNotEquals(-1L, token.ino());
        } else {
            assertTrue(token.value().matches("-?\\d+:-?\\d+"), token.value());
            assertFalse(token.strong());
        }
    }

    @Test
    void versionTokenChangesWhenContentChanges() throws IOException {
        VersionToken before = VersionToken.of(file);
        Files.writeString(file, "改了内容", StandardCharsets.UTF_8);
        VersionToken after = VersionToken.of(file);

        assertNotEquals(before, after);
    }

    @Test
    void versionTokenIsStableForUntouchedFile() throws IOException {
        assertEquals(VersionToken.of(file), VersionToken.of(file));
    }

    @Test
    void observeRecordsTheToken() throws IOException {
        ObserveGate.Observation observation = gate.observe(file);

        assertTrue(observation.existed());
        assertEquals(VersionToken.of(file), observation.token());
        assertTrue(gate.observed(file));
    }

    @Test
    void observeRecordsAbsence() throws IOException {
        Path fresh = dir.resolve("fresh.txt");

        ObserveGate.Observation observation = gate.observe(fresh);

        assertFalse(observation.existed());
        assertEquals(null, observation.token());
    }
}
