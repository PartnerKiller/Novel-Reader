package com.reader.Novel.Reader.listener;

import com.reader.Novel.Reader.model.User;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ActiveSessionListener implements HttpSessionListener, HttpSessionAttributeListener {

    private static final AtomicInteger activeSessions = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, User> activeUsersMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastActiveMap = new ConcurrentHashMap<>();

    // Keep active threshold to 2 minutes for precision activity monitoring
    private static final long ACTIVE_THRESHOLD_MS = 120000; 

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        activeSessions.incrementAndGet();
        updateLastActive(se.getSession().getId());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        if (activeSessions.get() > 0) {
            activeSessions.decrementAndGet();
        }
        String sessionId = se.getSession().getId();
        activeUsersMap.remove(sessionId);
        lastActiveMap.remove(sessionId);
    }

    @Override
    public void attributeAdded(HttpSessionBindingEvent event) {
        if ("user".equals(event.getName())) {
            Object val = event.getValue();
            if (val instanceof User) {
                String sessionId = event.getSession().getId();
                activeUsersMap.put(sessionId, (User) val);
                updateLastActive(sessionId);
            }
        }
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent event) {
        if ("user".equals(event.getName())) {
            activeUsersMap.remove(event.getSession().getId());
        }
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent event) {
        if ("user".equals(event.getName())) {
            Object val = event.getValue();
            if (val instanceof User) {
                String sessionId = event.getSession().getId();
                activeUsersMap.put(sessionId, (User) val);
                updateLastActive(sessionId);
            }
        }
    }

    public static void updateLastActive(String sessionId) {
        lastActiveMap.put(sessionId, System.currentTimeMillis());
    }

    public static void registerActiveUser(String sessionId, User user) {
        activeUsersMap.put(sessionId, user);
    }

    public static int getActiveSessionsCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (String sessionId : lastActiveMap.keySet()) {
            Long lastActive = lastActiveMap.get(sessionId);
            if (lastActive != null && (now - lastActive) <= ACTIVE_THRESHOLD_MS) {
                count++;
            }
        }
        return count > 0 ? count : 1;
    }

    public static List<User> getActiveUsersList() {
        long now = System.currentTimeMillis();
        java.util.Map<Long, User> distinctUsers = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, User> entry : activeUsersMap.entrySet()) {
            String sessionId = entry.getKey();
            User u = entry.getValue();
            Long lastActive = lastActiveMap.get(sessionId);
            if (u != null && u.getId() != null && lastActive != null && (now - lastActive) <= ACTIVE_THRESHOLD_MS) {
                distinctUsers.put(u.getId(), u);
            }
        }
        return new ArrayList<>(distinctUsers.values());
    }
}
