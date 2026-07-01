package com.reader.Novel.Reader.configuration;

import com.reader.Novel.Reader.model.User;
import com.reader.Novel.Reader.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class UserStatusInterceptor implements HandlerInterceptor {

    @Autowired
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(true);
        User sessionUser = (User) session.getAttribute("user");
        
        if (sessionUser == null) {
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (jakarta.servlet.http.Cookie cookie : cookies) {
                    if ("remember_me".equals(cookie.getName())) {
                        String cookieValue = cookie.getValue();
                        if (cookieValue != null && cookieValue.contains(":")) {
                            String[] parts = cookieValue.split(":", 2);
                            try {
                                Long userId = Long.parseLong(parts[0]);
                                String token = parts[1];
                                
                                User dbUser = userService.getUserById(userId);
                                if (dbUser != null && !Boolean.TRUE.equals(dbUser.getBanned())) {
                                    String expectedData = dbUser.getId() + ":" + dbUser.getPassword() + ":YukiTalesSecretRememberMeKey";
                                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                                    byte[] hash = digest.digest(expectedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    StringBuilder hexString = new StringBuilder();
                                    for (byte b : hash) {
                                        String hex = Integer.toHexString(0xff & b);
                                        if (hex.length() == 1) hexString.append('0');
                                        hexString.append(hex);
                                    }
                                    if (hexString.toString().equals(token)) {
                                        session.setAttribute("user", dbUser);
                                        sessionUser = dbUser;
                                    }
                                }
                            } catch (Exception e) {
                                // ignore invalid cookie
                            }
                        }
                        break;
                    }
                }
            }
        }
        
        if (sessionUser != null && sessionUser.getId() != null) {
            User dbUser = userService.getUserById(sessionUser.getId());
            if (dbUser == null || dbUser.getId() == null) {
                session.invalidate();
                jakarta.servlet.http.Cookie rmCookie = new jakarta.servlet.http.Cookie("remember_me", "");
                rmCookie.setMaxAge(0);
                rmCookie.setPath("/");
                response.addCookie(rmCookie);
                
                String uri = request.getRequestURI();
                if (uri.startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    PrintWriter writer = response.getWriter();
                    writer.write("{\"error\":\"User session expired or user deleted.\"}");
                    writer.flush();
                    return false;
                } else {
                    response.sendRedirect("/?error=" + URLEncoder.encode("Your session has expired.", StandardCharsets.UTF_8));
                    return false;
                }
            }
            if (dbUser != null && dbUser.getId() != null) {
                boolean isBanned = Boolean.TRUE.equals(dbUser.getBanned());
                boolean isTimedOut = dbUser.getTimeoutUntil() != null && dbUser.getTimeoutUntil().isAfter(LocalDateTime.now());
                
                if (isBanned || isTimedOut) {
                    session.invalidate();
                    
                    // Clear remember_me cookie on ban/timeout
                    jakarta.servlet.http.Cookie rmCookie = new jakarta.servlet.http.Cookie("remember_me", "");
                    rmCookie.setMaxAge(0);
                    rmCookie.setPath("/");
                    response.addCookie(rmCookie);
                    
                    String errorMsg = isBanned ? "Your account has been banned." : "Your account is temporarily timed out.";
                    
                    String uri = request.getRequestURI();
                    if (uri.startsWith("/api/")) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        PrintWriter writer = response.getWriter();
                        writer.write("{\"error\":\"" + errorMsg + "\"}");
                        writer.flush();
                        return false;
                    } else {
                        response.sendRedirect("/?error=" + URLEncoder.encode(errorMsg, StandardCharsets.UTF_8));
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
