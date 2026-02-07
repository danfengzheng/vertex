package com.vertex.framework.socket.server;

import com.vertex.framework.socket.core.SocketSession;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话注册表
 * <p>
 * 管理所有已连接的客户端会话
 */
@Slf4j
public class SessionRegistry {

    private final Map<String, SocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册会话
     */
    public void register(SocketSession session) {
        sessions.put(session.getId(), session);
        log.info("Session registered: {}, total: {}", session.getId(), sessions.size());
    }

    /**
     * 注销会话
     */
    public void unregister(String sessionId) {
        SocketSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("Session unregistered: {}, total: {}", sessionId, sessions.size());
        }
    }

    /**
     * 获取会话
     */
    public SocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取所有会话
     */
    public Collection<SocketSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * 当前连接数
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * 广播消息给所有会话
     */
    public void broadcast(String message) {
        sessions.values().forEach(session -> {
            if (session.isActive()) {
                session.send(message);
            }
        });
    }

    /**
     * 关闭所有会话
     */
    public void closeAll() {
        sessions.values().forEach(SocketSession::close);
        sessions.clear();
        log.info("All sessions closed");
    }
}
