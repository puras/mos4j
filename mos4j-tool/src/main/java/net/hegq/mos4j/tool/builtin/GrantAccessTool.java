package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiConsumer;

/**
 * grant_access 工具（T5.5）：模型申请给文件 jail 加根。
 *
 * <p><b>职责边界是这条工具的命门</b>：模型只有「申请」的能力，没有「判定」的能力。
 * 能不能加、加哪个目录，由宿主的审批回调（人工或宿主策略）决定；
 * 本工具把申请原样转给回调，批准了才真的加根。
 * 自己判「能不能加」的模型等于自己给自己发钥匙。
 *
 * <p>审批通过也<b>不缓存</b>：每次申请都走一遍回调，与 J2 的审批纪律一致。
 */
public final class GrantAccessTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "grant_access";

    /** 审批决定：批准加根。 */
    public static final String VERDICT_APPROVED = "approved";
    /** 审批决定：拒绝申请。 */
    public static final String VERDICT_REJECTED = "rejected";

    /**
     * 宿主审批回调：参数是（申请的目录路径, 模型给的理由），返回批准与否。
     *
     * <p>返回 {@code true} = 批准；{@code false} 或抛异常 = 拒绝（异常不外泄，转成拒绝理由）。
     */
    @FunctionalInterface
    public interface AccessApprover {
        boolean approve(String path, String reason);
    }

    private final RootSet roots;
    private final SandboxedFs fs;
    private final AccessApprover approver;

    /**
     * @param roots    活根集合（批准后加到这里）
     * @param fs       文件 jail（校验挂载名与目录）
     * @param approver 宿主审批回调（必填；没有审批回调就不该装这条工具）
     */
    public GrantAccessTool(RootSet roots, SandboxedFs fs, AccessApprover approver) {
        if (roots == null || fs == null || approver == null) {
            throw new IllegalArgumentException("tool: grant_access 需要 roots、fs 与 approver");
        }
        this.roots = roots;
        this.fs = fs;
        this.approver = approver;
    }

    /**
     * 参数。
     *
     * @param mount  挂载名（之后模型用它访问该根，如 {@code data/logs/...}）
     * @param dir    要开放的目录（宿主文件系统上的绝对路径；模型给的申请原样传给审批人）
     * @param reason 申请理由（给审批人看）
     */
    public record Params(
            @ToolParam(desc = "挂载名（之后以 <挂载名>/... 访问该目录）", required = true) String mount,
            @ToolParam(desc = "要开放的目录（宿主文件系统路径）", required = true) String dir,
            @ToolParam(desc = "申请理由（给审批人看）") String reason) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "申请开放一个宿主目录为可访问根（需要人工批准）。批准后即可用 <挂载名>/路径 访问该目录下的文件。"
                + "申请不会自动通过：请给出清晰的理由。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.mount() == null || params.mount().isBlank()) {
            throw new ToolException("grant_access 失败：缺少参数 mount");
        }
        if (params.dir() == null || params.dir().isBlank()) {
            throw new ToolException("grant_access 失败：缺少参数 dir");
        }
        // 挂载名只允许一个路径段：多段或含分隔符会让挂载语义变得含糊
        String mount = params.mount().trim();
        if (mount.contains("/") || mount.contains("\\") || mount.contains("..")
                || mount.equals(RootSet.ANCHOR_ID)) {
            throw new ToolException("grant_access 失败：挂载名必须是单段名称（不含 / \\ ..）: " + mount);
        }

        boolean approved;
        try {
            approved = approver.approve(params.dir(),
                    params.reason() == null ? "" : params.reason());
        } catch (RuntimeException e) {
            // 审批回调挂了 ≠ 批准：转成拒绝，并把异常原因说明（fail-closed）
            return "申请未通过（审批通道异常，fail-closed）: " + e.getMessage();
        }
        if (!approved) {
            return "申请未通过：宿主拒绝开放 \"" + params.dir() + "\"。"
                    + "不要尝试用其他路径或方式访问同一目录；如确有必要，请向用户说明后再重新申请";
        }

        // 批准后才动文件系统：目录必须存在且是目录
        Path dir;
        try {
            dir = Paths.get(params.dir().trim());
        } catch (RuntimeException e) {
            throw new ToolException("grant_access 失败：路径非法: " + params.dir());
        }
        if (!Files.isDirectory(dir)) {
            throw new ToolException("grant_access 失败：目录不存在或不是目录（审批已通过，但无法挂载）: "
                    + params.dir());
        }
        try {
            roots.add(mount, dir);
        } catch (java.io.IOException | RuntimeException e) {
            throw new ToolException("grant_access 失败：挂载失败 " + e.getMessage());
        }
        return "已开放 \"" + dir + "\" 为根，挂载名 \"" + mount + "\"。"
                + "现在可以用 " + mount + "/相对路径 访问该目录下的文件";
    }
}
