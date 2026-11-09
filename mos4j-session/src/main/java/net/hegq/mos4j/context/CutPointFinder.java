package net.hegq.mos4j.context;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.session.Projection;

import java.util.List;

/**
 * 切割点搜索：压缩时从哪一条事件开始保留。
 *
 * <p><b>切割点永不落在 tool 结果上</b>（机制 M8）：tool 结果必须紧跟在声明它的
 * assistant 之后。如果保留段以一条 tool 结果开头，投影出来的历史就是
 * 「有回执、没有调用」——服务商直接拒收整段对话。
 *
 * <p><b>怎么修</b>：把切割点**往前挪**到声明它的 assistant 事件，
 * 调用方与回执一起被遮蔽（同生共死）。找不到声明者（历史已经被裁过）时，
 * 把这条孤儿回执也一起遮蔽（往后挪）——宁可少留一条，也不能留一条非法的。
 *
 * <p><b>为什么不能往后挪到 tool 结果之后</b>：那样 assistant 被遮蔽、回执被保留，
 * 同样非法，而且更隐蔽（看起来「保留了更多」）。
 */
public final class CutPointFinder {

    private CutPointFinder() {
    }

    /**
     * 切割结果。
     *
     * @param index        保留段在投影视图中的起始下标
     * @param firstKeptSeq 保留段首条事件的序号（遮蔽线：小于它的消息被遮蔽）
     * @param keptTokens   保留段的 token 估算
     */
    public record Cut(int index, long firstKeptSeq, int keptTokens) {
    }

    /**
     * 找切割点。
     *
     * @param view            投影视图（带事件序号）
     * @param keepRecentTokens 保留的最近历史 token 数
     * @return 切割点；没有可遮蔽区间时返回 {@code null}
     */
    public static Cut find(List<Projection.SeqMessage> view, int keepRecentTokens) {
        if (view == null || view.isEmpty()) {
            return null;
        }
        // 从尾部往前累计：累计到 keepRecentTokens 之前的那条就是切割点
        int acc = 0;
        int cut = view.size();
        for (int i = view.size() - 1; i >= 0; i--) {
            acc += estimate(view.get(i).message());
            if (acc >= keepRecentTokens) {
                cut = i;
                break;
            }
        }
        if (cut >= view.size()) {
            // 整段历史都不够 keepRecentTokens：没有可遮蔽的区间
            return null;
        }
        // 合法性修正：保留段不能以 tool 结果开头
        while (cut < view.size() && role(view.get(cut)) == Role.TOOL) {
            int declarer = findDeclarer(view, cut);
            if (declarer >= 0) {
                cut = declarer;
            } else {
                cut = cut + 1;
            }
        }
        if (cut <= 0 || cut >= view.size()) {
            // cut == 0：遮蔽区间为空（压了等于没压）
            // cut >= size：保留段为空（历史本来就短）
            return null;
        }
        int kept = 0;
        for (int i = cut; i < view.size(); i++) {
            kept += estimate(view.get(i).message());
        }
        return new Cut(cut, view.get(cut).seq(), kept);
    }

    /**
     * 往前找声明这条 tool 结果的 assistant 消息。
     *
     * @param view 投影视图
     * @param cut  tool 结果所在下标
     * @return assistant 下标；找不到返回 -1
     */
    private static int findDeclarer(List<Projection.SeqMessage> view, int cut) {
        String callId = view.get(cut).message().toolCallId();
        if (callId == null || callId.isEmpty()) {
            return -1;
        }
        for (int i = cut - 1; i >= 0; i--) {
            Message message = view.get(i).message();
            if (message.role() != Role.ASSISTANT || message.toolCalls() == null) {
                continue;
            }
            for (ToolCall call : message.toolCalls()) {
                if (callId.equals(call.id())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Role role(Projection.SeqMessage sm) {
        return sm.message() == null ? null : sm.message().role();
    }

    /**
     * 估算一条消息的 token 数（chars/4，与会话侧口径一致）。
     *
     * @param message 消息
     * @return token 估算
     */
    static int estimate(Message message) {
        if (message == null) {
            return 0;
        }
        int n = message.content() == null ? 0 : message.content().length();
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                n += call.name() == null ? 0 : call.name().length();
                n += call.args() == null ? 0 : call.args().length();
            }
        }
        return (n + 3) / 4;
    }
}
