package com.reader.Novel.Reader.controller;

import com.reader.Novel.Reader.model.User;
import com.reader.Novel.Reader.service.UserService;
import com.reader.Novel.Reader.service.NovelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.servlet.http.HttpSession;

@Controller
public class AdminController {

    @Autowired
    private UserService userService;

    @Autowired
    private NovelService novelService;

    @GetMapping("/admin")
    public String adminDashboard(HttpSession session, Model model) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return "redirect:/?showLogin=true";
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return "redirect:/"; // Unauthorized, kick out to homepage
        }

        boolean isOwner = "OWNER".equals(role);

        // Admin-only data (or owner)
        if ("ADMIN".equals(role) || "OWNER".equals(role)) {
            java.util.List<User> filteredUsers = userService.getUsers().stream()
                .filter(u -> isOwner || !"OWNER".equals(u.getUser_type()))
                .collect(java.util.stream.Collectors.toList());
            model.addAttribute("users", filteredUsers);
        }

        // Shared admin/editor data
        if ("EDITOR".equals(role)) {
            model.addAttribute("novels", novelService.getNovelsByCreatorId(loggedInUser.getId()));
        } else {
            model.addAttribute("novels", novelService.getAllNovels());
        }
        model.addAttribute("featuredNovelId", novelService.getFeaturedNovelId());
        model.addAttribute("userRole", role);

        java.util.List<User> editors = userService.getUsers().stream()
            .filter(u -> "EDITOR".equals(u.getUser_type()))
            .toList();
        model.addAttribute("editors", editors);

        return "admin";
    }
}
