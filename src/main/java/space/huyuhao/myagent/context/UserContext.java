package space.huyuhao.myagent.context;

import java.util.concurrent.ConcurrentHashMap;

public class UserContext {
    private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> usernameHolder = new ThreadLocal<>();

    /**
     * 用于 reactive/stream 场景下跨线程传递 userId。
     * key = conversationId, value = userId
     */
    private static final ConcurrentHashMap<String, Long> conversationUserMap = new ConcurrentHashMap<>();

    public static void setUserId(Long userId) {
        userIdHolder.set(userId);
    }

    public static Long getUserId() {
        return userIdHolder.get();
    }

    public static void setUsername(String username) {
        usernameHolder.set(username);
    }

    public static String getUsername() {
        return usernameHolder.get();
    }

    public static void clear() {
        userIdHolder.remove();
        usernameHolder.remove();
    }

    /**
     * 在请求线程中调用，将当前用户的 conversationId 与 userId 绑定，
     * 以便 reactive 流式处理线程中能通过 conversationId 找回 userId。
     */
    public static void registerConversationUser(String conversationId) {
        Long userId = getUserId();
        if (userId != null && conversationId != null) {
            conversationUserMap.put(conversationId, userId);
        }
    }

    /**
     * 作为 ThreadLocal 的 fallback，通过 conversationId 获取 userId。
     */
    public static Long getUserIdByConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return conversationUserMap.get(conversationId);
    }

    /**
     * 清理指定会话的用户映射
     */
    public static void removeConversationUser(String conversationId) {
        if (conversationId != null) {
            conversationUserMap.remove(conversationId);
        }
    }
}
