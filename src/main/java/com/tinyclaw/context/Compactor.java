package com.tinyclaw.context;

import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责监控和压缩上下文内存，防止大模型发生 OOM（Context Window 溢出）。
 * <p>
 * 采用阶梯降级（Staged Degradation）策略：对远期历史执行 Observation Masking，
 * 对短期保护区超长内容执行 Head-Tail Truncation，System Prompt 永远保留。
 * 使用字符数作为 Token 估算指标，零外部依赖。
 */
public class Compactor {

    private static final Logger log = LoggerFactory.getLogger(Compactor.class);

    /**
     * Head-Tail 截断时保留的总字符数
     */
    private static final int MAX_KEEP = 1000;

    /**
     * Head-Tail 截断时每一端保留的字符数
     */
    private static final int HALF_KEEP = MAX_KEEP / 2;

    /**
     * 远期历史中触发掩码的 ToolResult 内容长度阈值
     */
    private static final int MASK_THRESHOLD = 200;

    /**
     * 触发压缩的最大字符数阈值（水位线）
     */
    private final int maxChars;

    /**
     * Working Memory 保护区：最近的 N 条消息
     */
    private final int retainLastMsgs;

    /**
     * @param maxChars        触发压缩的最大字符数阈值
     * @param retainLastMsgs  Working Memory 保护区消息条数
     */
    public Compactor(int maxChars, int retainLastMsgs) {
        this.maxChars = maxChars;
        this.retainLastMsgs = retainLastMsgs;
    }

    /**
     * 接收准备发送给大模型的消息数组，如果总长度超标，对远期历史区进行掩码，对短期保护区进行截断。
     * <p>
     * 注意：本方法返回压缩后的新列表，不会修改原消息（Message 为 record 不可变，但需保护 ToolCalls 内容）。
     *
     * @param msgs 原始消息列表
     * @return 压缩后的消息列表（未超标则返回原列表）
     */
    public List<Message> compact(List<Message> msgs) {
        int currentLength = estimateLength(msgs);

        // 如果没有超过水位线，直接返回
        if (currentLength < maxChars) {
            return msgs;
        }

        log.warn("[Compactor] 内存告警：当前上下文长度 ({} 字符) 超过阈值 ({})，触发压缩清理...", currentLength, maxChars);

        int msgCount = msgs.size();
        // 计算受保护的 Working Memory 起始索引
        int protectStartIndex = Math.max(0, msgCount - retainLastMsgs);

        List<Message> compacted = new ArrayList<>(msgCount);

        for (int i = 0; i < msgCount; i++) {
            Message msg = msgs.get(i);

            // 1. System Prompt 绝对不能动，直接保留
            if (msg.role() == Role.SYSTEM) {
                compacted.add(msg);
                continue;
            }

            boolean isInWorkingMemory = i >= protectStartIndex;
            Message newMsg = msg;

            // 2. 工具返回结果 (Observation / ToolResult)：Role=USER 且 toolCallId 不为空
            if (msg.role() == Role.USER && msg.toolCallId() != null && !msg.toolCallId().isEmpty()) {
                if (!isInWorkingMemory) {
                    // 【第一道防线：远期历史】无情掩码替换
                    if (msg.content() != null && msg.content().length() > MASK_THRESHOLD) {
                        int originalLen = msg.content().length();
                        newMsg = new Message(Role.USER,
                                "...[为了节省内存，早期的工具输出已被系统强制清理。原始长度: " + originalLen + " 字节]...",
                                msg.toolCalls(), msg.toolCallId());
                    }
                } else {
                    // 【第二道防线：短期记忆】Head-Tail Truncation
                    if (msg.content() != null && msg.content().length() > MAX_KEEP) {
                        String content = msg.content();
                        String head = content.substring(0, HALF_KEEP);
                        String tail = content.substring(content.length() - HALF_KEEP);
                        int truncated = content.length() - MAX_KEEP;
                        newMsg = new Message(Role.USER,
                                head + "\n\n...[内容过长，中间 " + truncated + " 字节已被系统截断]...\n\n" + tail,
                                msg.toolCalls(), msg.toolCallId());
                    }
                }
            }
            // 3. 远期的大模型冗长推理（Thinking Trace）
            else if (msg.role() == Role.ASSISTANT && msg.content() != null && !msg.content().isEmpty()) {
                if (!isInWorkingMemory && msg.content().length() > MASK_THRESHOLD) {
                    newMsg = new Message(Role.ASSISTANT,
                            "...[早期的推理思考过程已折叠]...",
                            msg.toolCalls(), msg.toolCallId());
                }
            }

            // 绝不触碰 msg.toolCalls，这是模型行动的证据，维系逻辑链的关键
            compacted.add(newMsg);
        }

        int newLength = estimateLength(compacted);
        log.warn("[Compactor] 压缩完成。上下文长度从 {} 降至 {} 字符。", currentLength, newLength);

        return compacted;
    }

    /**
     * 粗略计算当前上下文的总字符长度。
     * <p>
     * 累加所有消息的 content 长度 + ToolCall 的 name 和 arguments 长度。
     *
     * @param msgs 消息列表
     * @return 总字符长度估算值
     */
    private static int estimateLength(List<Message> msgs) {
        int length = 0;
        for (Message msg : msgs) {
            if (msg.content() != null) {
                length += msg.content().length();
            }
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    length += tc.name().length();
                    if (tc.arguments() != null) {
                        length += tc.arguments().toString().length();
                    }
                }
            }
        }
        return length;
    }
}
