package net.hegq.mos4j.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 活根集合：运行期可增长的 jail 根清单。
 *
 * <p><b>三条语义</b>（容易搞混，逐条说清）：
 * <ul>
 *   <li><b>锚点稳定</b>：相对路径的基准目录（anchor）在创建时定下，<b>永远不变</b>。
 *       加根是<b>追加</b>，不是「换根」——锚点漂移比「新根只能用绝对路径」更危险：
 *       宿主以为模型还在原目录里干活的，实际它已经换了工作区。</li>
 *   <li><b>加根对下一次调用立即生效</b>：不缓存解析结果，每次解析都读当前快照。
 *       审批通过后加的根，如果还要等到「下一轮」才生效，中间那次调用就会白白失败。</li>
 *   <li><b>变更留痕</b>：每次加根都记一条变更（{@link #changes()}），
 *       事后能查「这个会话里到底开放过哪些目录」。</li>
 * </ul>
 *
 * <p><b>并发</b>：读多写少，用读写锁保护；写操作（加根）不会与解析互相踩。
 */
public final class RootSet {

    /** 锚点根的挂载名（空串）。 */
    public static final String ANCHOR_ID = "";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Path anchor;
    private final List<Root> roots = new ArrayList<>();
    private final List<String> changes = new ArrayList<>();

    private RootSet(Path anchorReal) {
        this.anchor = anchorReal;
        this.roots.add(new Root(ANCHOR_ID, anchorReal, Instant.now()));
        this.changes.add("init anchor=" + anchorReal);
    }

    /**
     * 以某个目录为锚点创建根集合。
     *
     * @param anchor 锚点目录（必须存在；会被解析成真实路径）
     * @return 根集合
     * @throws IOException 锚点不存在或不可读
     */
    public static RootSet of(Path anchor) throws IOException {
        if (anchor == null) {
            throw new IllegalArgumentException("fs: 锚点目录不能为 null");
        }
        Path real = anchor.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("fs: 锚点不是目录: " + anchor);
        }
        return new RootSet(real);
    }

    /**
     * 追加一个根。
     *
     * <p>追加是<b>追加</b>：锚点不动，已有根不动。挂载名重复时直接报错——
     * 静默覆盖会让「模型以为访问的是 A 目录，实际是 B 目录」。
     *
     * @param id  挂载名（不能与已有根重复；锚点根用空串）
     * @param dir 根目录（必须存在）
     * @return this（便于链式）
     * @throws IOException 根不存在或不可读
     */
    public RootSet add(String id, Path dir) throws IOException {
        if (id == null) {
            throw new IllegalArgumentException("fs: 挂载名不能为 null");
        }
        if (dir == null) {
            throw new IllegalArgumentException("fs: 根目录不能为 null");
        }
        Path real = dir.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("fs: 根不是目录: " + dir);
        }
        lock.writeLock().lock();
        try {
            for (Root root : roots) {
                if (root.id().equals(id)) {
                    throw new IllegalStateException("fs: 挂载名重复: " + id);
                }
            }
            roots.add(new Root(id, real, Instant.now()));
            changes.add("add " + (id.isEmpty() ? ANCHOR_ID : id) + " " + real);
        } finally {
            lock.writeLock().unlock();
        }
        return this;
    }

    /**
     * 锚点目录（相对路径的基准，永不变动）。
     *
     * @return 锚点真实路径
     */
    public Path anchor() {
        return anchor;
    }

    /**
     * 当前全部根（快照）。
     *
     * @return 根列表（锚点根在前）
     */
    public List<Root> roots() {
        lock.readLock().lock();
        try {
            return List.copyOf(roots);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 变更留痕（审计用）。
     *
     * @return 变更记录（按时间顺序）
     */
    public List<String> changes() {
        lock.readLock().lock();
        try {
            return List.copyOf(changes);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按路径首段挑挂载点：首段与某个挂载名相同就用那个根，并把首段<b>吃掉</b>
     * （挂载名是挂载点本身，不是路径的一部分）；否则用锚点根。
     *
     * @param relativePath 相对路径
     * @return 挂载点（根 + 已消费的段数）
     */
    public Mount mountFor(Path relativePath) {
        List<Root> snapshot = roots();
        if (relativePath != null) {
            for (Path part : relativePath) {
                String name = part.toString();
                for (Root root : snapshot) {
                    if (!root.isAnchor() && root.id().equals(name)) {
                        return new Mount(root, 1);
                    }
                }
                break; // 只看首段
            }
        }
        return new Mount(snapshot.get(0), 0);
    }

    /**
     * 挂载点：选中的根 + 被消费的路径段数。
     *
     * @param root     选中的根
     * @param consumed 被吃掉的段数（挂载名那一段）
     */
    public record Mount(Root root, int consumed) {
    }

    @Override
    public String toString() {
        return "RootSet" + roots();
    }
}
