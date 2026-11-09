import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.PathScopeProbe;
import net.hegq.mos4j.sandbox.PathScopes;
import net.hegq.mos4j.sandbox.RunRequest;
import net.hegq.mos4j.sandbox.RunResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * macOS Seatbelt **强制效果**真机验收探针。
 *
 * <p><b>为什么需要它</b>：本仓库里那条自动化用例
 * {@code SandboxBoundaryTest.seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt}
 * 只能在「允许应用沙箱」的 macOS 主机上从「跳过」变成「通过」；
 * 在受限主机上它只能如实跳过——那**不是**验收。
 * 这个脚本把同一件事做成「拿到另一台 macOS 上就能直接跑」的形态，
 * 并把逐项结论打到 stdout，便于把结果回填进 `docs/contract/J8-STATUS.md`。
 *
 * <p><b>怎么跑</b>（在允许应用沙箱的 macOS 主机上）：
 * <pre>
 *   ./mvnw -Dmaven.repo.local=.m2repo -B -pl mos4j-runtime -am -DskipTests package
 *   java -cp mos4j-sandbox/target/classes tools/seatbelt-acceptance/SeatbeltAcceptance.java /tmp/mos4j-accept
 * </pre>
 *
 * <p><b>退出码</b>：0 = 强制效果已验证；1 = 强制没生效（真失败）；2 = 这台机器不允许应用沙箱（未验证，不是失败）。
 *
 * <p><b>判定口径</b>（三条各自独立，不允许用一条替另一条背书）：
 * <ol>
 *   <li>命令**真的跑起来了**（写根内的 marker 存在）——否则「写根外没写」是假绿；</li>
 *   <li>写根**外**的文件没被创建——这是强制生效的正向证据；</li>
 *   <li>写根**内**的文件能创建——否则可能是「什么都没跑」而不是「拦住了」。</li>
 * </ol>
 */
public final class SeatbeltAcceptance {

    private SeatbeltAcceptance() {
    }

    /**
     * @param args 可选：工作目录（缺省用 {@code target/seatbelt-acceptance}）
     */
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length > 0 ? args[0] : "target/seatbelt-acceptance")
                .toAbsolutePath();
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Path marker = allowed.resolve("marker.txt");
        Path forbidden = base.resolve("forbidden.txt");
        Files.deleteIfExists(marker);
        Files.deleteIfExists(forbidden);

        System.out.println("macOS Seatbelt 强制效果验收");
        System.out.println("  平台后端状态: " + PathScopes.status());
        System.out.println("  工作目录: " + base);

        // 1. 探测：能不能真的应用沙箱（非 macOS / Linux / 缺后端都会在这里判为「未验证」）
        PathScopeProbe.Result probe = PathScopes.probe();
        System.out.println("[1] 探测: available=" + probe.available() + " detail=" + probe.detail());
        if (!probe.available()) {
            System.out.println("→ 这台机器不允许应用 Seatbelt（" + probe.detail() + "）");
            System.out.println("   强制效果无法在本机验收；换一台允许应用沙箱的 macOS 再跑本脚本。");
            System.out.println("退出码 2：未验证（不是失败）");
            System.exit(2);
        }

        // 2. 走真实链路跑一条命令：写根内写 marker，写根外写 forbidden
        LocalRunner runner = LocalRunner.builder(allowed)
                .pathScope(PathScopes.derive(allowed))
                .build();
        RunResult result = runner.run(RunRequest.of(
                "echo ok > " + marker + " ; echo bad > " + forbidden));
        System.out.println("[2] 命令: started=" + result.started()
                + " exit=" + result.exitCode()
                + " startFailure=" + result.startFailure()
                + " stderr=" + result.stderr());
        if (!result.started()) {
            System.out.println("→ 命令根本没起来（路径强制装不上？）：" + result.startFailure());
            System.out.println("退出码 2：未验证（不是失败）");
            System.exit(2);
        }

        boolean markerExists = Files.exists(marker);
        boolean forbiddenExists = Files.exists(forbidden);

        // 3. 三条独立判定
        boolean ran = markerExists;
        boolean wroteInside = markerExists;
        boolean blockedOutside = !forbiddenExists;

        System.out.println("[3] 判定:");
        System.out.println("    命令真的跑起来了（写根内 marker 存在）: " + markerExists);
        System.out.println("    写根内可写: " + wroteInside);
        System.out.println("    写根外写不进去（forbidden 未被创建）: " + blockedOutside);

        if (!ran) {
            System.out.println("→ 命令没有真正执行，强制效果**未验证**（不判失败，也不判通过）");
            System.out.println("退出码 2：未验证（不是失败）");
            System.exit(2);
        }
        if (wroteInside && blockedOutside) {
            System.out.println("→ ✅ Seatbelt 强制效果已验证：写根内可写，写根外被拦");
            System.out.println("退出码 0：已验证");
            System.exit(0);
        }
        System.out.println("→ ❌ 强制没有生效：写根外的文件被创建了 " + forbidden);
        System.out.println("退出码 1：真失败");
        System.exit(1);
    }
}
