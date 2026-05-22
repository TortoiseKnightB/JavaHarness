package com.tinyclaw.engine;

import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 一次持续的人机交互过程，负责维护该会话的完整历史。
 * <p>
 * 通过读写锁保证并发安全，所有历史追加和读取操作均为线程安全。
 */
public class Session {

    /**
     * 会话唯一标识（如飞书 chatId、终端 session ID 等）
     */
    private final String id;

    /**
     * 该会话绑定的物理工作区目录
     */
    private final String workDir;

    /**
     * 会话创建时间
     */
    private final LocalDateTime createdAt;

    /**
     * 会话最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 此 Session 中所有的用户输入、大模型回复和工具调用结果
     */
    private final List<Message> history;

    /**
     * 读写锁，防止并发读写历史时发生 Data Race
     */
    private final ReadWriteLock mu;

    /**
     * @param id      会话唯一标识
     * @param workDir 会话绑定的工作区目录
     */
    public Session(String id, String workDir) {
        this.id = id;
        this.workDir = workDir;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.history = new ArrayList<>();
        this.mu = new ReentrantReadWriteLock();
    }

    /**
     * 线程安全地向 Session 中追加消息。
     *
     * @param msgs 要追加的消息
     */
    public void append(Message... msgs) {
        mu.writeLock().lock();
        try {
            for (Message msg : msgs) {
                history.add(msg);
            }
            updatedAt = LocalDateTime.now();
        } finally {
            mu.writeLock().unlock();
        }
    }

    /**
     * 获取短期工作记忆：从后往前截取最近的 limit 条消息。
     * <p>
     * 不返回全量历史，而是截取最近 N 条，形成 Agent 的"短期工作记忆"。
     * 关键防线：如果截断后的首条消息是孤儿 ToolResult（没有对应的 ToolCall），
     * 必须丢弃，否则大模型 API 会报 400 Bad Request。
     *
     * @param limit 保留的最近消息条数，≤0 表示全量返回
     * @return 截取后的消息列表（新 List，修改不影响原历史）
     */
    public List<Message> getWorkingMemory(int limit) {
        mu.readLock().lock();
        try {
            int total = history.size();
            if (total <= limit || limit <= 0) {
                return new ArrayList<>(history);
            }

            List<Message> res = new ArrayList<>(history.subList(total - limit, total));

            // 丢弃首条孤儿 ToolResult：Role=USER 且 toolCallId 不为空
            while (!res.isEmpty()) {
                Message first = res.get(0);
                if (first.role() == Role.USER && first.toolCallId() != null && !first.toolCallId().isEmpty()) {
                    res.remove(0);
                } else {
                    break;
                }
            }

            return res;
        } finally {
            mu.readLock().unlock();
        }
    }

    public String id() {
        return id;
    }

    public String workDir() {
        return workDir;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    /**
     * 全局会话管理器，用于多用户/多终端物理隔离。
     * <p>
     * 通过 ConcurrentHashMap 实现高并发下的线程安全，无需额外加锁。
     * 当请求到来时，根据请求来源（如终端目录哈希、飞书 ChatID）分配或唤醒对应的 Session 实例。
     */
    public static class SessionManager {

        private final ConcurrentHashMap<String, Session> sessions;

        public SessionManager() {
            this.sessions = new ConcurrentHashMap<>();
        }

        /**
         * 获取或创建一个会话。
         *
         * @param id      会话唯一标识
         * @param workDir 会话绑定的工作区目录
         * @return 已存在或新创建的 Session 实例
         */
        public Session getOrCreate(String id, String workDir) {
            return sessions.computeIfAbsent(id, k -> new Session(k, workDir));
        }
    }
}
