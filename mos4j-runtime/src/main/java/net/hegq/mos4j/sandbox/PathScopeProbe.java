package net.hegq.mos4j.sandbox;

/**
 * 路径强制的可用性探针：问一句「这台机器能不能真的把路径强制起来」。
 *
 * <p><b>为什么需要它</b>：平台上有后端 ≠ 这台机器上后端能用。macOS 的
 * {@code sandbox-exec} 在很多受管环境里存在，但宿主策略拒绝应用沙箱
 * （{@code sandbox_apply: Operation not permitted}）——那时候命令会失败，
 * 但那是**运行期**才发现的事：模型在第 N 轮说「命令失败了」，宿主拿到的是一次异常回执。
 * 装配期探一次，失败就能在启动时把话说清楚。
 *
 * <p><b>探针可注入</b>：测试环境常常就是「有 {@code sandbox-exec} 但不许 apply」，
 * 所以宿主（与测试）可以注入自己的探针，不必真跑一次沙箱。
 */
@FunctionalInterface
public interface PathScopeProbe {

    /**
     * 探测结果。
     *
     * @param available 是否可用
     * @param detail    说明（可用时说「可用在哪」，不可用时说「为什么不行」——进 Describe 的 caveat）
     */
    record Result(boolean available, String detail) {

        /**
         * @param detail 说明
         * @return 可用
         */
        public static Result available(String detail) {
            return new Result(true, detail);
        }

        /**
         * @param detail 说明
         * @return 不可用
         */
        public static Result unavailable(String detail) {
            return new Result(false, detail);
        }
    }

    /**
     * 探一次。
     *
     * @return 探测结果
     */
    Result probe();
}
