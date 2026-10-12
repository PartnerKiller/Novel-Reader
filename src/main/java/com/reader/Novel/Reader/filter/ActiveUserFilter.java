package com.reader.Novel.Reader.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class ActiveUserFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                String sessionId = session.getId();
                com.reader.Novel.Reader.listener.ActiveSessionListener.updateLastActive(sessionId);
                Object u = session.getAttribute("user");
                if (u instanceof com.reader.Novel.Reader.model.User) {
                    com.reader.Novel.Reader.listener.ActiveSessionListener.registerActiveUser(sessionId, (com.reader.Novel.Reader.model.User) u);
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void destroy() {}
}
