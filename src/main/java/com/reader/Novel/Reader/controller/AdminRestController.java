package com.reader.Novel.Reader.controller;

import com.reader.Novel.Reader.model.User;
import com.reader.Novel.Reader.model.Novel;
import com.reader.Novel.Reader.model.Chapter;
import com.reader.Novel.Reader.model.Purchase;
import com.reader.Novel.Reader.model.FlakePackage;
import com.reader.Novel.Reader.model.Review;
import com.reader.Novel.Reader.model.Notification;
import com.reader.Novel.Reader.model.Coupon;
import com.reader.Novel.Reader.repository.ReviewRepository;
import com.reader.Novel.Reader.repository.NotificationRepository;
import com.reader.Novel.Reader.repository.CouponRepository;
import com.reader.Novel.Reader.service.UserService;
import com.reader.Novel.Reader.service.NovelService;
import com.reader.Novel.Reader.service.SseService;
import com.reader.Novel.Reader.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminRestController {

    @Autowired
    private UserService userService;

    @Autowired
    private com.reader.Novel.Reader.repository.UserRepository userRepository;

    @Autowired
    private NovelService novelService;

    @Autowired
    private SseService sseService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private com.reader.Novel.Reader.repository.SystemSettingRepository systemSettingRepository;

    @Autowired
    private com.reader.Novel.Reader.service.EmailService emailService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentService paymentService;

    private boolean isRestricted(HttpSession session) {
        if (novelService.isSecuredMode()) {
            User loggedInUser = (User) session.getAttribute("user");
            return loggedInUser == null || !"OWNER".equals(loggedInUser.getUser_type());
        }
        return false;
    }

    @PostMapping("/system-state")
    public ResponseEntity<?> toggleSelfDestruct(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        if (!"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        novelService.toggleSecuredMode();
        boolean current = novelService.isSecuredMode();
        return ResponseEntity.ok(Map.of("success", true, "securedMode", current));
    }

    @GetMapping("/system-state/status")
    public ResponseEntity<?> getSelfDestructStatus(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        if (!"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        return ResponseEntity.ok(Map.of("securedMode", novelService.isSecuredMode()));
    }

    // 1. Promote/Demote User Role (ADMIN only)
    @PostMapping("/users/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestParam String role,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can modify roles."));
        }

        User userToModify = userService.getUserById(id);
        if (userToModify.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        // Protect OWNER accounts from being modified
        if ("OWNER".equals(userToModify.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot modify an owner account."));
        }

        // Prevent self-demotion (unless you are the OWNER)
        if (loggedInUser.getId().equals(userToModify.getId()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot demote yourself."));
        }

        // Block assigning the OWNER role (unless you are the OWNER)
        if ("OWNER".equals(role) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot assign the owner role."));
        }

        // Validate role input
        if (!"READER".equals(role) && !"EDITOR".equals(role) && !"ADMIN".equals(role) && !"PROOFREADER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role specified."));
        }

        userToModify.setUser_type(role);
        userService.updateUser(userToModify);

        try {
            sseService.sendGlobalEvent("user_role_updated", Map.of("userId", id, "role", role));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "User role updated successfully."));
    }

    // 1.2. Create User Account (ADMIN only)
    @PostMapping("/users/add")
    public ResponseEntity<?> createUser(
            @RequestParam String name,
            @RequestParam(required = false) String username,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam String role,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can create users."));
        }

        if (name == null || name.trim().isEmpty() ||
            email == null || email.trim().isEmpty() ||
            password == null || password.trim().isEmpty() ||
            role == null || role.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "All fields are required."));
        }

        java.util.Optional<User> existing = userService.getUserByEmail(email.trim());
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Email is already registered."));
        }

        if (username != null && !username.trim().isEmpty()) {
            if (userRepository.findByUsernameIgnoreCase(username.trim()).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username is already taken."));
            }
        }

        // Block assigning the OWNER role
        if ("OWNER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot assign the owner role."));
        }

        // Validate role input
        if (!"READER".equals(role) && !"EDITOR".equals(role) && !"ADMIN".equals(role) && !"PROOFREADER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role specified."));
        }

        User user = new User(null, name.trim(), email.trim(), com.reader.Novel.Reader.util.PasswordUtils.hashPassword(password), role);
        if (username != null && !username.trim().isEmpty()) {
            user.setUsername(username.trim());
        }
        userService.addUser(user);

        try {
            sseService.sendGlobalEvent("user_created", user);
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "User account created successfully.", "user", user));
    }

    // 1.3. Update User Balance (ADMIN only)
    @PostMapping("/users/{id}/balance")
    public ResponseEntity<?> updateUserBalance(
            @PathVariable Long id,
            @RequestParam Integer balance,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can modify user balances."));
        }

        if (balance == null || balance < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid balance value."));
        }

        User userToModify = userService.getUserById(id);
        if (userToModify.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        // Protect OWNER accounts from being modified
        if ("OWNER".equals(userToModify.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot modify an owner account."));
        }

        userToModify.setBalance(balance);
        userService.updateUser(userToModify);

        try {
            sseService.sendGlobalEvent("user_balance_updated", Map.of("userId", id, "balance", balance));
        } catch (Exception e) {
            // Log or ignore
        }

        // Update session if editing self
        if (loggedInUser.getId().equals(userToModify.getId())) {
            loggedInUser.setBalance(balance);
            session.setAttribute("user", loggedInUser);
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "User balance updated successfully."));
    }

    // 1.4. Edit User Details (ADMIN only)
    @PostMapping("/users/edit/{id}")
    public ResponseEntity<?> editUser(
            @PathVariable Long id,
            @RequestParam String name,
            @RequestParam(required = false) String username,
            @RequestParam String email,
            @RequestParam(required = false) String password,
            @RequestParam String role,
            @RequestParam(required = false) Long newUserId,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can edit user accounts."));
        }

        User userToModify = userService.getUserById(id);
        if (userToModify.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        // Protect OWNER accounts from being modified by non-owners
        if ("OWNER".equals(userToModify.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot modify an owner account."));
        }

        // Prevent OWNER from demoting themselves or any OWNER demotion (unless logged in user is OWNER)
        if ("OWNER".equals(userToModify.getUser_type()) && !"OWNER".equals(role) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot demote an owner."));
        }

        // Prevent self-demotion for admin
        if (loggedInUser.getId().equals(userToModify.getId()) && !"OWNER".equals(loggedInUser.getUser_type()) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot demote yourself."));
        }

        // Block assigning the OWNER role to non-owner accounts (unless logged in user is OWNER)
        if ("OWNER".equals(role) && !"OWNER".equals(userToModify.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot assign the owner role."));
        }

        // Validate role input
        if (!"READER".equals(role) && !"EDITOR".equals(role) && !"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role specified."));
        }

        // Check if email already registered by another user
        java.util.Optional<User> existing = userService.getUserByEmail(email.trim());
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Email is already registered by another user."));
        }

        if (username != null && !username.trim().isEmpty()) {
            java.util.Optional<User> existingUsername = userRepository.findByUsernameIgnoreCase(username.trim());
            if (existingUsername.isPresent() && !existingUsername.get().getId().equals(id)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username is already registered by another user."));
            }
        }

        // Handle primary key ID update if a new ID is provided and different from the current ID
        if (newUserId != null && !newUserId.equals(id)) {
            User existingUserWithNewId = userService.getUserById(newUserId);
            if (existingUserWithNewId.getId() != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The ID " + newUserId + " is already in use by another user."));
            }

            userService.updateUserId(id, newUserId);

            // Update session if editing self
            if (loggedInUser.getId().equals(id)) {
                loggedInUser.setId(newUserId);
                session.setAttribute("user", loggedInUser);
            }

            // Fetch the updated user object using the new ID so subsequent updates apply to it
            userToModify = userService.getUserById(newUserId);
        }

        userToModify.setName(name.trim());
        if (username != null && !username.trim().isEmpty()) {
            userToModify.setUsername(username.trim());
        }
        userToModify.setEmail(email.trim());
        userToModify.setUser_type(role);
        if (password != null && !password.trim().isEmpty()) {
            userToModify.setPassword(com.reader.Novel.Reader.util.PasswordUtils.hashPassword(password.trim()));
        }

        userService.updateUser(userToModify);

        try {
            sseService.sendGlobalEvent("user_updated", Map.of("oldUserId", id, "user", userToModify));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "User details updated successfully."));
    }

    // 1.5. Delete User (ADMIN only)
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can delete users."));
        }

        User userToDelete = userService.getUserById(id);
        if (userToDelete.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        // Protect OWNER accounts from being deleted
        if ("OWNER".equals(userToDelete.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot delete an owner account."));
        }

        // Prevent self-deletion (unless OWNER decides to)
        if (loggedInUser.getId().equals(userToDelete.getId()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot delete yourself."));
        }

        userService.deleteUser(id);

        try {
            sseService.sendGlobalEvent("user_deleted", Map.of("userId", id));
        } catch (Exception e) {
            // Log or ignore to avoid blocking response on SSE failure
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "User deleted successfully."));
    }

    // Ban User (ADMIN/OWNER only)
    @PostMapping("/users/{id}/ban")
    public ResponseEntity<?> banUser(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can ban users."));
        }

        User userToBan = userService.getUserById(id);
        if (userToBan.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        if ("OWNER".equals(userToBan.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot ban an owner account."));
        }

        if (loggedInUser.getId().equals(userToBan.getId()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot ban yourself."));
        }

        userToBan.setBanned(true);
        userService.updateUser(userToBan);

        return ResponseEntity.ok(Map.of("success", true, "message", "User banned successfully."));
    }

    // Unban User (ADMIN/OWNER only)
    @PostMapping("/users/{id}/unban")
    public ResponseEntity<?> unbanUser(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can unban users."));
        }

        User userToUnban = userService.getUserById(id);
        if (userToUnban.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        userToUnban.setBanned(false);
        userService.updateUser(userToUnban);

        return ResponseEntity.ok(Map.of("success", true, "message", "User unbanned successfully."));
    }

    // Timeout User (ADMIN/OWNER only)
    @PostMapping("/users/{id}/timeout")
    public ResponseEntity<?> timeoutUser(
            @PathVariable Long id,
            @RequestParam Integer durationMinutes,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can timeout users."));
        }

        User userToTimeout = userService.getUserById(id);
        if (userToTimeout.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        if ("OWNER".equals(userToTimeout.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Cannot timeout an owner account."));
        }

        if (loggedInUser.getId().equals(userToTimeout.getId()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "You cannot timeout yourself."));
        }

        if (durationMinutes <= 0) {
            userToTimeout.setTimeoutUntil(null);
        } else {
            userToTimeout.setTimeoutUntil(java.time.LocalDateTime.now().plusMinutes(durationMinutes));
        }
        userService.updateUser(userToTimeout);

        return ResponseEntity.ok(Map.of("success", true, "message", "User timeout updated successfully."));
    }

    // Bulk Delete Users (ADMIN/OWNER only)
    @PostMapping("/users/bulk-delete")
    public ResponseEntity<?> bulkDeleteUsers(
            @RequestBody Map<String, List<Long>> payload,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can delete users."));
        }

        List<Long> ids = payload.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user IDs provided."));
        }

        int count = 0;
        for (Long id : ids) {
            if (loggedInUser.getId().equals(id) && !"OWNER".equals(loggedInUser.getUser_type())) continue; // skip self
            User u = userService.getUserById(id);
            if (u != null && u.getId() != null && !"OWNER".equals(u.getUser_type())) {
                userService.deleteUser(id);
                try {
                    sseService.sendGlobalEvent("user_deleted", Map.of("userId", id));
                } catch (Exception e) {}
                count++;
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", count + " users deleted successfully."));
    }

    // Bulk Ban Users (ADMIN/OWNER only)
    @PostMapping("/users/bulk-ban")
    public ResponseEntity<?> bulkBanUsers(
            @RequestBody Map<String, List<Long>> payload,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can ban users."));
        }

        List<Long> ids = payload.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user IDs provided."));
        }

        int count = 0;
        for (Long id : ids) {
            if (loggedInUser.getId().equals(id) && !"OWNER".equals(loggedInUser.getUser_type())) continue; // skip self
            User u = userService.getUserById(id);
            if (u != null && u.getId() != null && !"OWNER".equals(u.getUser_type())) {
                u.setBanned(true);
                userService.updateUser(u);
                count++;
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", count + " users banned successfully."));
    }

    // Bulk Unban Users (ADMIN/OWNER only)
    @PostMapping("/users/bulk-unban")
    public ResponseEntity<?> bulkUnbanUsers(
            @RequestBody Map<String, List<Long>> payload,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can unban users."));
        }

        List<Long> ids = payload.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user IDs provided."));
        }

        int count = 0;
        for (Long id : ids) {
            User u = userService.getUserById(id);
            if (u != null && u.getId() != null) {
                u.setBanned(false);
                userService.updateUser(u);
                count++;
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", count + " users unbanned successfully."));
    }

    // Bulk Timeout Users (ADMIN/OWNER only)
    @PostMapping("/users/bulk-timeout")
    public ResponseEntity<?> bulkTimeoutUsers(
            @RequestBody Map<String, Object> payload,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can timeout users."));
        }

        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) payload.get("ids");
        Object rawDuration = payload.get("durationMinutes");
        if (rawIds == null || rawIds.isEmpty() || rawDuration == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User IDs and duration are required."));
        }

        Integer durationMinutes = Integer.parseInt(rawDuration.toString());
        int count = 0;
        for (Object rawId : rawIds) {
            Long id = Long.parseLong(rawId.toString());
            if (loggedInUser.getId().equals(id) && !"OWNER".equals(loggedInUser.getUser_type())) continue; // skip self
            User u = userService.getUserById(id);
            if (u != null && u.getId() != null && !"OWNER".equals(u.getUser_type())) {
                if (durationMinutes <= 0) {
                    u.setTimeoutUntil(null);
                } else {
                    u.setTimeoutUntil(java.time.LocalDateTime.now().plusMinutes(durationMinutes));
                }
                userService.updateUser(u);
                count++;
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", count + " users timeout updated."));
    }

    @PostMapping("/stories/add")
    public ResponseEntity<?> addStory(
            @RequestParam String title,
            @RequestParam String author,
            @RequestParam String description,
            @RequestParam String coverUrl,
            @RequestParam String type,
            @RequestParam String genre,
            @RequestParam Double rating,
            @RequestParam String status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String countryOfOrigin,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long creatorId,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can create stories."));
        }

        if (title == null || title.trim().isEmpty() ||
            author == null || author.trim().isEmpty() ||
            coverUrl == null || coverUrl.trim().isEmpty() ||
            type == null || type.trim().isEmpty() ||
            genre == null || genre.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required story fields."));
        }

        String resolvedCoverUrl = handleLocalCoverUrl(coverUrl.trim());
        Novel novel = new Novel(null, title.trim(), author.trim(), description.trim(), resolvedCoverUrl, type.toUpperCase(), genre.trim(), rating, status);
        novel.setCreatorId(creatorId != null ? creatorId : loggedInUser.getId());
        novel.setYear(year);
        novel.setTags(tags);
        novel.setCountryOfOrigin(countryOfOrigin);
        novel.setSource(source);
        Novel saved = novelService.saveNovel(novel);
        syncNovelFoldersAndFiles(saved);

        try {
            sseService.sendGlobalEvent("story_created", Map.ofEntries(
                Map.entry("id", saved.getId()),
                Map.entry("title", saved.getTitle()),
                Map.entry("author", saved.getAuthor()),
                Map.entry("description", saved.getDescription() != null ? saved.getDescription() : ""),
                Map.entry("coverUrl", saved.getCoverUrl()),
                Map.entry("type", saved.getType()),
                Map.entry("genre", saved.getGenre()),
                Map.entry("rating", saved.getRating() != null ? saved.getRating() : 0.0),
                Map.entry("status", saved.getStatus()),
                Map.entry("creatorId", saved.getCreatorId() != null ? saved.getCreatorId() : 0L),
                Map.entry("year", saved.getYear() != null ? saved.getYear() : ""),
                Map.entry("tags", saved.getTags() != null ? saved.getTags() : ""),
                Map.entry("countryOfOrigin", saved.getCountryOfOrigin() != null ? saved.getCountryOfOrigin() : ""),
                Map.entry("source", saved.getSource() != null ? saved.getSource() : ""),
                Map.entry("editorSelection", saved.getEditorSelection() != null ? saved.getEditorSelection() : false)
            ));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "novel", saved));
    }

    private boolean isValidImageSignature(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        // PNG Signature: 89 50 4E 47
        if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 && bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47) {
            return true;
        }
        // JPEG/JPG Signature: FF D8 FF
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return true;
        }
        // GIF Signature: 47 49 46 38 ('GIF8')
        if (bytes[0] == (byte) 0x47 && bytes[1] == (byte) 0x49 && bytes[2] == (byte) 0x46 && bytes[3] == (byte) 0x38) {
            return true;
        }
        // WEBP Signature: RIFF....WEBP (offset 0: 'RIFF', offset 8: 'WEBP')
        if (bytes.length >= 12 &&
            bytes[0] == (byte) 'R' && bytes[1] == (byte) 'I' && bytes[2] == (byte) 'F' && bytes[3] == (byte) 'F' &&
            bytes[8] == (byte) 'W' && bytes[9] == (byte) 'E' && bytes[10] == (byte) 'B' && bytes[11] == (byte) 'P') {
            return true;
        }
        return false;
    }

    // 2.5. Upload story cover image (ADMIN & EDITOR)
    @PostMapping("/stories/upload-cover")
    public ResponseEntity<?> uploadCover(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can upload covers."));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please select a file to upload."));
        }

        // Validate file type (image check)
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed."));
        }

        try {
            byte[] bytes = file.getBytes();
            if (!isValidImageSignature(bytes)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file signature. Only PNG, JPEG, GIF, and WEBP images are allowed."));
            }
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueName = UUID.randomUUID().toString() + extension;

            String userDir = System.getProperty("user.dir");
            Path srcDir = Paths.get(userDir, "src", "main", "resources", "static", "uploads");
            Path rootUploadsDir = Paths.get(userDir, "uploads");

            // Create directories if they do not exist
            Files.createDirectories(srcDir);
            Files.createDirectories(rootUploadsDir);

            // Paths to files
            Path srcFile = srcDir.resolve(uniqueName);
            Path rootFile = rootUploadsDir.resolve(uniqueName);

            Files.write(srcFile, bytes);
            Files.write(rootFile, bytes);

            return ResponseEntity.ok(Map.of("success", true, "url", "/uploads/" + uniqueName));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    // 2.7. Upload general image (cover or content) (ADMIN & EDITOR)
    @PostMapping("/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can upload images."));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please select a file to upload."));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed."));
        }

        try {
            byte[] bytes = file.getBytes();
            if (!isValidImageSignature(bytes)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file signature. Only PNG, JPEG, GIF, and WEBP images are allowed."));
            }
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueName = UUID.randomUUID().toString() + extension;

            String userDir = System.getProperty("user.dir");
            Path srcDir = Paths.get(userDir, "src", "main", "resources", "static", "uploads");
            Path rootUploadsDir = Paths.get(userDir, "uploads");

            Files.createDirectories(srcDir);
            Files.createDirectories(rootUploadsDir);

            Path srcFile = srcDir.resolve(uniqueName);
            Path rootFile = rootUploadsDir.resolve(uniqueName);

            Files.write(srcFile, bytes);
            Files.write(rootFile, bytes);

            return ResponseEntity.ok(Map.of("success", true, "url", "/uploads/" + uniqueName));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }


    // 3. Add Chapter to Story (ADMIN & EDITOR)
    @PostMapping("/chapters/add")
    public ResponseEntity<?> addChapter(
            @RequestParam Long novelId,
            @RequestParam String title,
            @RequestParam Double chapterNumber,
            @RequestParam String content,
            @RequestParam(defaultValue = "0") Integer price,
            @RequestParam(required = false) String publishAt,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can add chapters."));
        }

        Novel novel = novelService.getNovelById(novelId);
        if (novel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(novel.getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own this story."));
        }

        if (title == null || title.trim().isEmpty() ||
            chapterNumber == null ||
            content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required chapter fields."));
        }

        // Check if chapter number already exists for this story to avoid duplication issues
        List<Chapter> existingChaps = novelService.getChaptersByNovelId(novelId);
        boolean duplicate = existingChaps.stream().anyMatch(c -> c.getChapterNumber().equals(chapterNumber));
        if (duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Chapter number " + chapterNumber + " already exists for this story."));
        }

        Chapter chapter = new Chapter(null, novel, title.trim(), chapterNumber, content.trim(), price);
        boolean canEditPublishAt = "ADMIN".equals(role) || "OWNER".equals(role) || "PROOFREADER".equals(role);
        if (canEditPublishAt && publishAt != null && !publishAt.trim().isEmpty()) {
            try {
                chapter.setPublishAt(java.time.LocalDateTime.parse(publishAt));
            } catch (Exception e) {
                // ignore parsing error
            }
        }
        Chapter saved = chapterServiceHelper(chapter);
        syncChapterFiles(saved);

        try {
            Novel n = saved.getNovel();
            sseService.sendGlobalEvent("chapter_created", Map.of(
                "id", saved.getId(),
                "novelId", novelId,
                "title", saved.getTitle() != null ? saved.getTitle() : "",
                "chapterNumber", saved.getChapterNumber(),
                "price", saved.getPrice() != null ? saved.getPrice() : 0,
                "publishAt", saved.getPublishAt() != null ? saved.getPublishAt().toString() : "",
                "novelTitle", n != null && n.getTitle() != null ? n.getTitle() : "",
                "novelCoverUrl", n != null && n.getCoverUrl() != null ? n.getCoverUrl() : ""
            ));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "chapter", saved));
    }

    // 4. Retrieve chapters for a specific story series (ADMIN & EDITOR)
    @GetMapping("/stories/{id}/chapters")
    public ResponseEntity<?> getStoryChapters(@PathVariable Long id, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        Novel novel = novelService.getNovelById(id);
        if (novel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(novel.getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        List<Chapter> chapters = novelService.getChaptersByNovelId(id);
        return ResponseEntity.ok(chapters);
    }

    // 5. Edit story series (ADMIN & EDITOR)
    @PostMapping("/stories/edit/{id}")
    public ResponseEntity<?> editStory(
            @PathVariable Long id,
            @RequestParam String title,
            @RequestParam String author,
            @RequestParam String description,
            @RequestParam String coverUrl,
            @RequestParam String type,
            @RequestParam String genre,
            @RequestParam Double rating,
            @RequestParam String status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String countryOfOrigin,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long creatorId,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can edit stories."));
        }

        Novel existingNovel = novelService.getNovelById(id);
        if (existingNovel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(existingNovel.getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own this story."));
        }

        if (title == null || title.trim().isEmpty() ||
            author == null || author.trim().isEmpty() ||
            coverUrl == null || coverUrl.trim().isEmpty() ||
            type == null || type.trim().isEmpty() ||
            genre == null || genre.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields."));
        }

        existingNovel.setTitle(title.trim());
        existingNovel.setAuthor(author.trim());
        existingNovel.setDescription(description.trim());
        
        String resolvedCoverUrl = handleLocalCoverUrl(coverUrl.trim());
        existingNovel.setCoverUrl(resolvedCoverUrl);
        
        existingNovel.setType(type.toUpperCase());
        existingNovel.setGenre(genre.trim());
        existingNovel.setRating(rating);
        existingNovel.setStatus(status);
        existingNovel.setYear(year);
        existingNovel.setTags(tags);
        existingNovel.setCountryOfOrigin(countryOfOrigin);
        existingNovel.setSource(source);
        if (creatorId != null && ("ADMIN".equals(role) || "OWNER".equals(role))) {
            existingNovel.setCreatorId(creatorId);
        }

        Novel saved = novelService.saveNovel(existingNovel);
        syncNovelFoldersAndFiles(saved);
        try {
            sseService.sendGlobalEvent("story_updated", Map.ofEntries(
                Map.entry("id", saved.getId()),
                Map.entry("title", saved.getTitle()),
                Map.entry("author", saved.getAuthor()),
                Map.entry("description", saved.getDescription() != null ? saved.getDescription() : ""),
                Map.entry("coverUrl", saved.getCoverUrl()),
                Map.entry("type", saved.getType()),
                Map.entry("genre", saved.getGenre()),
                Map.entry("rating", saved.getRating() != null ? saved.getRating() : 0.0),
                Map.entry("status", saved.getStatus()),
                Map.entry("creatorId", saved.getCreatorId() != null ? saved.getCreatorId() : 0L),
                Map.entry("year", saved.getYear() != null ? saved.getYear() : ""),
                Map.entry("tags", saved.getTags() != null ? saved.getTags() : ""),
                Map.entry("countryOfOrigin", saved.getCountryOfOrigin() != null ? saved.getCountryOfOrigin() : ""),
                Map.entry("source", saved.getSource() != null ? saved.getSource() : ""),
                Map.entry("editorSelection", saved.getEditorSelection() != null ? saved.getEditorSelection() : false)
            ));
        } catch (Exception e) {
            // Log or ignore
        }
        return ResponseEntity.ok(Map.of("success", true, "novel", saved));
    }

    // 6. Edit chapter (ADMIN & EDITOR)
    @PostMapping("/chapters/edit/{id}")
    public ResponseEntity<?> editChapter(
            @PathVariable Long id,
            @RequestParam String title,
            @RequestParam Double chapterNumber,
            @RequestParam String content,
            @RequestParam(defaultValue = "0") Integer price,
            @RequestParam(required = false) String publishAt,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can edit chapters."));
        }

        Chapter existingChapter = novelService.getChapterById(id);
        if (existingChapter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Chapter not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(existingChapter.getNovel().getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own the parent story."));
        }

        if (title == null || title.trim().isEmpty() ||
            chapterNumber == null ||
            content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields."));
        }

        // Check if chapter number changed and if the new number already exists for this story (excluding itself)
        if (!existingChapter.getChapterNumber().equals(chapterNumber)) {
            List<Chapter> existingChaps = novelService.getChaptersByNovelId(existingChapter.getNovel().getId());
            boolean duplicate = existingChaps.stream()
                .anyMatch(c -> !c.getId().equals(id) && c.getChapterNumber().equals(chapterNumber));
            if (duplicate) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Chapter number " + chapterNumber + " already exists for this story."));
            }
        }

        existingChapter.setTitle(title.trim());
        existingChapter.setChapterNumber(chapterNumber);
        existingChapter.setContent(content.trim());
        existingChapter.setPrice(price);
        
        boolean canEditPublishAt = "ADMIN".equals(role) || "OWNER".equals(role) || "PROOFREADER".equals(role);
        if (canEditPublishAt) {
            if (publishAt != null && !publishAt.trim().isEmpty()) {
                try {
                    existingChapter.setPublishAt(java.time.LocalDateTime.parse(publishAt));
                } catch (Exception e) {
                    // ignore parsing error
                }
            } else {
                existingChapter.setPublishAt(null);
            }
        }

        Chapter saved = novelService.saveChapter(existingChapter);
        syncChapterFiles(saved);
        try {
            Novel n = saved.getNovel();
            sseService.sendGlobalEvent("chapter_updated", Map.of(
                "id", saved.getId(),
                "novelId", n.getId(),
                "title", saved.getTitle() != null ? saved.getTitle() : "",
                "chapterNumber", saved.getChapterNumber(),
                "price", saved.getPrice() != null ? saved.getPrice() : 0,
                "publishAt", saved.getPublishAt() != null ? saved.getPublishAt().toString() : "",
                "novelTitle", n.getTitle() != null ? n.getTitle() : "",
                "novelCoverUrl", n.getCoverUrl() != null ? n.getCoverUrl() : ""
            ));
        } catch (Exception e) {
            // Log or ignore
        }
        return ResponseEntity.ok(Map.of("success", true, "chapter", saved));
    }

    // 7. Delete chapter (ADMIN & EDITOR)
    @DeleteMapping("/chapters/{id}")
    public ResponseEntity<?> deleteChapter(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can delete chapters."));
        }

        Chapter chapter = novelService.getChapterById(id);
        if (chapter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Chapter not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(chapter.getNovel().getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own the parent story."));
        }

        Long novelId = chapter.getNovel().getId();
        novelService.deleteChapter(id);
        try {
            sseService.sendGlobalEvent("chapter_deleted", Map.of(
                "chapterId", id,
                "novelId", novelId
            ));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Chapter deleted successfully."));
    }

    // 2.8. Delete story series (manga/manhwa/novel/comic) (ADMIN & EDITOR)
    @DeleteMapping("/stories/{id}")
    public ResponseEntity<?> deleteStory(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can delete stories."));
        }

        Novel novel = novelService.getNovelById(id);
        if (novel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(novel.getCreatorId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own this story."));
        }

        novelService.deleteNovel(id);
        try {
            sseService.sendGlobalEvent("story_deleted", Map.of("storyId", id));
        } catch (Exception e) {
            // Log or ignore
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Story deleted successfully."));
    }

    // 2.8.b. Bulk Delete story series (ADMIN & EDITOR)
    @DeleteMapping("/stories/bulk")
    public ResponseEntity<?> deleteStoriesBulk(
            @RequestBody List<Long> ids,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only admins, editors, and proofreaders can delete stories."));
        }

        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No story IDs provided."));
        }

        // Validate all stories exist and user has permission to delete them
        for (Long id : ids) {
            Novel novel = novelService.getNovelById(id);
            if (novel == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story with ID " + id + " not found."));
            }
            if (!"ADMIN".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role) && !loggedInUser.getId().equals(novel.getCreatorId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You do not own story: " + novel.getTitle()));
            }
        }

        // Perform deletion
        for (Long id : ids) {
            novelService.deleteNovel(id);
            try {
                sseService.sendGlobalEvent("story_deleted", Map.of("storyId", id));
            } catch (Exception e) {
                // Ignore
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Selected stories deleted successfully."));
    }

    // 9. Analytics Summary (ADMIN & EDITOR)
    @GetMapping("/analytics/summary")
    public ResponseEntity<?> getAnalyticsSummary(HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.ok(Map.of(
                "totalSales", 0,
                "totalRevenue", 0,
                "activeReaders", 0,
                "salesByStory", List.of(),
                "salesOverTime", Map.of(),
                "salesByEditor", List.of()
            ));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        List<Novel> allNovels = novelService.getAllNovels();
        List<Chapter> allChapters = novelService.getAllChapters();
        List<Purchase> allPurchases = novelService.getAllPurchases();
        List<User> allUsers = userService.getUsers();

        // Map chapters by ID
        java.util.Map<Long, Chapter> chapterMap = new java.util.HashMap<>();
        for (Chapter c : allChapters) {
            chapterMap.put(c.getId(), c);
        }

        // Map novels by ID
        java.util.Map<Long, Novel> novelMap = new java.util.HashMap<>();
        for (Novel n : allNovels) {
            novelMap.put(n.getId(), n);
        }

        // Map users by ID
        java.util.Map<Long, User> userMap = new java.util.HashMap<>();
        for (User u : allUsers) {
            userMap.put(u.getId(), u);
        }

        boolean isAdmin = "ADMIN".equals(role) || "OWNER".equals(role);
        Long editorId = loggedInUser.getId();

        int totalSales = 0;
        int totalRevenue = 0;

        // Sales and revenue by story ID
        java.util.Map<Long, Integer> novelSalesCount = new java.util.HashMap<>();
        java.util.Map<Long, Integer> novelRevenue = new java.util.HashMap<>();

        // Revenue by editor user ID (for admin only)
        java.util.Map<Long, Integer> editorRevenueMap = new java.util.HashMap<>();

        // Daily revenue timeline
        java.util.Map<String, Integer> dailyRevenue = new java.util.TreeMap<>();

        for (Purchase p : allPurchases) {
            Chapter chap = chapterMap.get(p.getChapterId());
            if (chap == null) continue;

            Novel nov = chap.getNovel();
            if (nov == null) continue;
            Novel fullNovel = novelMap.get(nov.getId());
            if (fullNovel == null) continue;

            // Filter for EDITOR
            if (!isAdmin && !editorId.equals(fullNovel.getCreatorId())) {
                continue;
            }

            int price = chap.getPrice() != null ? chap.getPrice() : 0;
            totalSales++;
            totalRevenue += price;

            novelSalesCount.put(fullNovel.getId(), novelSalesCount.getOrDefault(fullNovel.getId(), 0) + 1);
            novelRevenue.put(fullNovel.getId(), novelRevenue.getOrDefault(fullNovel.getId(), 0) + price);

            if (isAdmin && fullNovel.getCreatorId() != null) {
                editorRevenueMap.put(fullNovel.getCreatorId(), editorRevenueMap.getOrDefault(fullNovel.getCreatorId(), 0) + price);
            }

            if (p.getPurchasedAt() != null) {
                String dateStr = p.getPurchasedAt().toLocalDate().toString();
                dailyRevenue.put(dateStr, dailyRevenue.getOrDefault(dateStr, 0) + price);
            }
        }

        // Prepare lists/maps for response
        java.util.List<java.util.Map<String, Object>> salesByStoryList = new java.util.ArrayList<>();
        for (Novel n : allNovels) {
            if (!isAdmin && !editorId.equals(n.getCreatorId())) {
                continue;
            }
            int sales = novelSalesCount.getOrDefault(n.getId(), 0);
            int rev = novelRevenue.getOrDefault(n.getId(), 0);
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("novelId", n.getId());
            item.put("title", n.getTitle());
            item.put("sales", sales);
            item.put("revenue", rev);
            salesByStoryList.add(item);
        }

        java.util.List<java.util.Map<String, Object>> salesByEditorList = new java.util.ArrayList<>();
        if (isAdmin) {
            for (java.util.Map.Entry<Long, Integer> entry : editorRevenueMap.entrySet()) {
                User ed = userMap.get(entry.getKey());
                String editorName = ed != null ? ed.getName() : "Editor #" + entry.getKey();
                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("editorId", entry.getKey());
                item.put("editorName", editorName);
                item.put("revenue", entry.getValue());
                salesByEditorList.add(item);
            }
        }

        // Unique readers count who purchased any of the filtered stories
        java.util.Set<Long> uniqueReaders = new java.util.HashSet<>();
        for (Purchase p : allPurchases) {
            Chapter chap = chapterMap.get(p.getChapterId());
            if (chap == null) continue;
            Novel nov = chap.getNovel();
            if (nov == null) continue;
            Novel fullNovel = novelMap.get(nov.getId());
            if (fullNovel == null) continue;

            if (isAdmin || editorId.equals(fullNovel.getCreatorId())) {
                uniqueReaders.add(p.getUserId());
            }
        }

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("totalSales", totalSales);
        response.put("totalRevenue", totalRevenue);
        response.put("activeReaders", uniqueReaders.size());
        response.put("salesByStory", salesByStoryList);
        response.put("salesOverTime", dailyRevenue);
        if (isAdmin) {
            response.put("salesByEditor", salesByEditorList);
        }

        return ResponseEntity.ok(response);
    }

    // 10. User Economic Analysis (ADMIN only)
    @GetMapping("/analytics/users")
    public ResponseEntity<?> getAnalyticsUsers(HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.ok(List.of());
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can access user analytics."));
        }

        List<User> rawUsers = userService.getUsers();
        java.util.Map<Long, User> userLookup = new java.util.HashMap<>();
        for (User u : rawUsers) {
            userLookup.put(u.getId(), u);
        }

        boolean isOwner = "OWNER".equals(loggedInUser.getUser_type());
        java.util.List<User> allUsers = rawUsers.stream()
            .filter(u -> isOwner || !"OWNER".equals(u.getUser_type()))
            .collect(java.util.stream.Collectors.toList());

        List<Purchase> allPurchases = novelService.getAllPurchases();
        List<Chapter> allChapters = novelService.getAllChapters();

        java.util.Map<Long, Chapter> chapterMap = new java.util.HashMap<>();
        for (Chapter c : allChapters) {
            chapterMap.put(c.getId(), c);
        }

        java.util.Map<Long, Integer> userSpent = new java.util.HashMap<>();
        java.util.Map<Long, Integer> userChaptersCount = new java.util.HashMap<>();

        for (Purchase p : allPurchases) {
            Long userId = p.getUserId();
            User purchaseUser = userLookup.get(userId);
            if (purchaseUser != null && "OWNER".equals(purchaseUser.getUser_type())) {
                continue; // Skip owner transaction analytics
            }
            Chapter chap = chapterMap.get(p.getChapterId());
            int price = (chap != null && chap.getPrice() != null) ? chap.getPrice() : 0;

            userSpent.put(userId, userSpent.getOrDefault(userId, 0) + price);
            userChaptersCount.put(userId, userChaptersCount.getOrDefault(userId, 0) + 1);
        }

        java.util.List<java.util.Map<String, Object>> usersList = new java.util.ArrayList<>();
        java.util.Set<Long> activeUserIds = new java.util.HashSet<>();

        for (User u : allUsers) {
            activeUserIds.add(u.getId());
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("userId", u.getId());
            item.put("name", u.getName());
            item.put("email", u.getEmail());
            item.put("role", u.getUser_type());
            item.put("balance", u.getBalance() != null ? u.getBalance() : 0);
            item.put("totalSpent", userSpent.getOrDefault(u.getId(), 0));
            item.put("chaptersUnlocked", userChaptersCount.getOrDefault(u.getId(), 0));
            usersList.add(item);
        }

        // Add deleted users who have transactions
        for (java.util.Map.Entry<Long, Integer> entry : userSpent.entrySet()) {
            Long uId = entry.getKey();
            if (uId != null && !activeUserIds.contains(uId)) {
                java.util.Map<String, Object> item = new java.util.HashMap<>();
                item.put("userId", uId);
                item.put("name", "Deleted User (ID: " + uId + ")");
                item.put("email", "deleted@yuki-tales.com");
                item.put("role", "DELETED");
                item.put("balance", 0);
                item.put("totalSpent", entry.getValue());
                item.put("chaptersUnlocked", userChaptersCount.getOrDefault(uId, 0));
                usersList.add(item);
            }
        }

        return ResponseEntity.ok(usersList);
    }



    private Chapter chapterServiceHelper(Chapter chapter) {
        return novelService.saveChapter(chapter);
    }

    private String handleLocalCoverUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.trim().isEmpty()) {
            return coverUrl;
        }
        
        coverUrl = coverUrl.trim();
        
        // If it's already a web URL or an uploaded file path, return it as is
        if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://") || coverUrl.startsWith("/uploads/")) {
            return coverUrl;
        }
        
        String userDir = System.getProperty("user.dir");
        Path matchedFile = findLocalFile(coverUrl, userDir);
        
        if (matchedFile != null) {
            try {
                String originalFilename = matchedFile.getFileName().toString();
                String extension = "";
                if (originalFilename.contains(".")) {
                    extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                }
                String uniqueName = UUID.randomUUID().toString() + extension;
                
                Path srcDir = Paths.get(userDir, "src", "main", "resources", "static", "uploads");
                Path targetDir = Paths.get(userDir, "target", "classes", "static", "uploads");
                Path rootUploadsDir = Paths.get(userDir, "uploads");
                
                Files.createDirectories(srcDir);
                Files.createDirectories(targetDir);
                Files.createDirectories(rootUploadsDir);
                
                Path srcFile = srcDir.resolve(uniqueName);
                Path targetFile = targetDir.resolve(uniqueName);
                Path rootFile = rootUploadsDir.resolve(uniqueName);
                
                Files.copy(matchedFile, srcFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(matchedFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(matchedFile, rootFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                return "/uploads/" + uniqueName;
            } catch (IOException e) {
                System.err.println("Failed to copy local cover: " + e.getMessage());
            }
        }
        
        return coverUrl;
    }

    private Path findLocalFile(String filename, String userDir) {
        try {
            Path directPath = Paths.get(filename);
            if (Files.exists(directPath) && !Files.isDirectory(directPath)) {
                return directPath;
            }
        } catch (Exception e) {}

        try {
            Path relativePath = Paths.get(userDir, filename);
            if (Files.exists(relativePath) && !Files.isDirectory(relativePath)) {
                return relativePath;
            }
        } catch (Exception e) {}

        try {
            String cleanName = Paths.get(filename).getFileName().toString().toLowerCase();
            File rootDir = new File(userDir);
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        String normName = name.replaceAll("[ūúùûü]", "u");
                        String normClean = cleanName.replaceAll("[ūúùûü]", "u");
                        if (name.equals(cleanName) || normName.equals(normClean) || name.contains(cleanName) || cleanName.contains(name)) {
                            return f.toPath();
                        }
                    }
                }
            }
        } catch (Exception e) {}
        
        return null;
    }

    // --- SNOW FLAKES PACKAGES ENDPOINTS (ADMIN only) ---

    @PostMapping("/flakes/add")
    public ResponseEntity<?> addFlakePackage(
            @RequestParam(required = false) Long id,
            @RequestParam Integer amount,
            @RequestParam Double price,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can configure pricing."));
        }

        if (amount == null || amount <= 0 || price == null || price <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount and price must be greater than zero."));
        }

        if (id != null) {
            FlakePackage existing = novelService.getFlakePackageById(id);
            if (existing != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Package ID " + id + " is already in use."));
            }
            try {
                jdbcTemplate.update("INSERT INTO flake_package (id, amount, price) VALUES (?, ?, ?)", id, amount, price);
                alignFlakePackageSequence();
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to create package: " + e.getMessage()));
            }
        } else {
            FlakePackage pack = new FlakePackage(null, amount, price);
            novelService.saveFlakePackage(pack);
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Package added successfully!"));
    }

    @PostMapping("/flakes/edit/{id}")
    public ResponseEntity<?> editFlakePackage(
            @PathVariable Long id,
            @RequestParam(required = false) Long newId,
            @RequestParam Integer amount,
            @RequestParam Double price,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can configure pricing."));
        }

        FlakePackage pack = novelService.getFlakePackageById(id);
        if (pack == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Package not found."));
        }

        if (amount == null || amount <= 0 || price == null || price <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount and price must be greater than zero."));
        }

        if (newId != null && !newId.equals(id)) {
            FlakePackage existing = novelService.getFlakePackageById(newId);
            if (existing != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Package ID " + newId + " is already in use."));
            }
            try {
                jdbcTemplate.update("UPDATE flake_package SET id = ?, amount = ?, price = ? WHERE id = ?", newId, amount, price, id);
                alignFlakePackageSequence();
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to update package ID: " + e.getMessage()));
            }
        } else {
            pack.setAmount(amount);
            pack.setPrice(price);
            novelService.saveFlakePackage(pack);
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Package details updated successfully!"));
    }

    private void alignFlakePackageSequence() {
        try {
            String dbName = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) conn -> conn.getMetaData().getDatabaseProductName());
            if ("H2".equalsIgnoreCase(dbName)) {
                jdbcTemplate.execute("ALTER TABLE flake_package ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM flake_package)");
            } else if (dbName != null && dbName.toUpperCase().contains("MYSQL")) {
                Long nextId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM flake_package", Long.class);
                if (nextId != null) {
                    jdbcTemplate.execute("ALTER TABLE flake_package AUTO_INCREMENT = " + nextId);
                }
            }
        } catch (Exception e) {
            System.err.println("Sequence alignment failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/flakes/{id}")
    public ResponseEntity<?> deleteFlakePackage(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can configure pricing."));
        }

        FlakePackage pack = novelService.getFlakePackageById(id);
        if (pack == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Package not found."));
        }

        novelService.deleteFlakePackage(id);

        return ResponseEntity.ok(Map.of("success", true, "message", "Package deleted successfully!"));
    }

    @PostMapping("/novels/{id}/feature")
    public ResponseEntity<?> toggleFeatureNovel(
            @PathVariable Long id,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }

        if (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only administrators can feature stories."));
        }

        Novel novel = novelService.getNovelById(id);
        if (novel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        Long currentFeatured = novelService.getFeaturedNovelId();
        if (id.equals(currentFeatured)) {
            novelService.setFeaturedNovelId(null);
            return ResponseEntity.ok(Map.of("success", true, "featured", false, "message", "Story unfeatured successfully!"));
        } else {
            novelService.setFeaturedNovelId(id);
            return ResponseEntity.ok(Map.of("success", true, "featured", true, "message", "Story featured successfully!"));
        }
    }



    private String getNovelFolderName(Novel novel) {
        if (novel == null) return "unknown";
        String folderName = novel.getTitle();
        if (folderName == null || folderName.trim().isEmpty()) {
            folderName = "story_" + novel.getId();
        } else {
            folderName = folderName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        }
        return folderName;
    }

    private String getChapterFolderName(Chapter chapter) {
        if (chapter == null) return "chapter_unknown";
        String folderName = "Chapter " + chapter.getChapterNumber();
        if (chapter.getTitle() != null && !chapter.getTitle().trim().isEmpty()) {
            folderName += " - " + chapter.getTitle();
        }
        return folderName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private void syncNovelFoldersAndFiles(Novel novel) {
        if (novel == null || novel.getId() == null) return;
        
        String userDir = System.getProperty("user.dir");
        String novelFolder = getNovelFolderName(novel);
        
        Path srcMainDir = Paths.get(userDir, "src", "main", "resources", "static", "uploads", novelFolder);
        Path targetDir = Paths.get(userDir, "target", "classes", "static", "uploads", novelFolder);
        Path rootBackupDir = Paths.get(userDir, "uploads", novelFolder);
        
        try {
            // Create cover picture directories
            Files.createDirectories(srcMainDir.resolve("cover picture"));
            Files.createDirectories(targetDir.resolve("cover picture"));
            Files.createDirectories(rootBackupDir.resolve("cover picture"));
            
            // Sync description to the root of the novel folder
            String desc = novel.getDescription() != null ? novel.getDescription() : "";
            Files.writeString(srcMainDir.resolve("description.txt"), desc);
            Files.writeString(targetDir.resolve("description.txt"), desc);
            Files.writeString(rootBackupDir.resolve("description.txt"), desc);
            
            // Sync cover image if local upload
            String coverUrl = novel.getCoverUrl();
            if (coverUrl != null && coverUrl.startsWith("/uploads/") && !coverUrl.contains("/cover picture/")) {
                String filename = coverUrl.substring(coverUrl.lastIndexOf("/") + 1);
                String sourcePath = coverUrl.substring("/uploads/".length());
                
                Path srcFile = Paths.get(userDir, "src", "main", "resources", "static", "uploads", sourcePath);
                Path targetFile = Paths.get(userDir, "target", "classes", "static", "uploads", sourcePath);
                Path rootFile = Paths.get(userDir, "uploads", sourcePath);
                
                // Fallback to root uploads/ if not found in subfolder
                if (!Files.exists(rootFile)) {
                    srcFile = Paths.get(userDir, "src", "main", "resources", "static", "uploads", filename);
                    targetFile = Paths.get(userDir, "target", "classes", "static", "uploads", filename);
                    rootFile = Paths.get(userDir, "uploads", filename);
                }
                
                String destFilename = "cover_" + filename;
                Path destSrcFile = srcMainDir.resolve("cover picture").resolve(destFilename);
                Path destTargetFile = targetDir.resolve("cover picture").resolve(destFilename);
                Path destRootFile = rootBackupDir.resolve("cover picture").resolve(destFilename);
                
                if (Files.exists(srcFile)) {
                    Files.copy(srcFile, destSrcFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(targetFile)) {
                    Files.copy(targetFile, destTargetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(rootFile)) {
                    Files.copy(rootFile, destRootFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                
                // Set the coverUrl URL pointing to the new cover picture folder
                String webUrlPath = "/uploads/" + novelFolder + "/cover picture/" + destFilename;
                novel.setCoverUrl(webUrlPath);
                novelService.saveNovel(novel);
            }
        } catch (IOException e) {
            System.err.println("Error synchronizing novel folders and files: " + e.getMessage());
        }
    }

    private void syncChapterFiles(Chapter chapter) {
        if (chapter == null || chapter.getNovel() == null) return;
        Novel novel = chapter.getNovel();
        
        // Ensure folders exist
        syncNovelFoldersAndFiles(novel);
        
        String userDir = System.getProperty("user.dir");
        String novelFolder = getNovelFolderName(novel);
        String chapterFolder = getChapterFolderName(chapter);
        
        Path srcTextDir = Paths.get(userDir, "src", "main", "resources", "static", "uploads", novelFolder, chapterFolder);
        Path targetTextDir = Paths.get(userDir, "target", "classes", "static", "uploads", novelFolder, chapterFolder);
        Path rootTextDir = Paths.get(userDir, "uploads", novelFolder, chapterFolder);
        
        try {
            Files.createDirectories(srcTextDir);
            Files.createDirectories(targetTextDir);
            Files.createDirectories(rootTextDir);
            
            // Sync chapter content images if any
            String content = chapter.getContent() != null ? chapter.getContent() : "";
            boolean contentChanged = false;
            
            // Scan for matches of pattern "/uploads/...img"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/uploads/([^\\s\"'>]+)");
            java.util.regex.Matcher matcher = pattern.matcher(content);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String sourcePath = matcher.group(1);
                // Make sure we don't match a path that is already in the target format
                String targetIndicator = novelFolder + "/" + chapterFolder + "/";
                if (!sourcePath.contains(targetIndicator)) {
                    String filename = sourcePath.substring(sourcePath.lastIndexOf("/") + 1);
                    
                    Path srcFile = Paths.get(userDir, "src", "main", "resources", "static", "uploads", sourcePath);
                    Path targetFile = Paths.get(userDir, "target", "classes", "static", "uploads", sourcePath);
                    Path rootFile = Paths.get(userDir, "uploads", sourcePath);
                    
                    // Fallback to root uploads/ if not found in subfolder
                    if (!Files.exists(rootFile)) {
                        srcFile = Paths.get(userDir, "src", "main", "resources", "static", "uploads", filename);
                        targetFile = Paths.get(userDir, "target", "classes", "static", "uploads", filename);
                        rootFile = Paths.get(userDir, "uploads", filename);
                    }
                    
                    Path destSrcFile = srcTextDir.resolve(filename);
                    Path destTargetFile = targetTextDir.resolve(filename);
                    Path destRootFile = rootTextDir.resolve(filename);
                    
                    if (Files.exists(srcFile)) {
                        Files.copy(srcFile, destSrcFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (Files.exists(targetFile)) {
                        Files.copy(targetFile, destTargetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (Files.exists(rootFile)) {
                        Files.copy(rootFile, destRootFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    
                    String newUrl = "/uploads/" + novelFolder + "/" + chapterFolder + "/" + filename;
                    matcher.appendReplacement(sb, newUrl);
                    contentChanged = true;
                } else {
                    matcher.appendReplacement(sb, matcher.group(0));
                }
            }
            matcher.appendTail(sb);
            
            if (contentChanged) {
                content = sb.toString();
                chapter.setContent(content);
                novelService.saveChapter(chapter);
            }
            
            String chapFilename = "chapter_" + chapter.getChapterNumber() + ".txt";
            Files.writeString(srcTextDir.resolve(chapFilename), content);
            Files.writeString(targetTextDir.resolve(chapFilename), content);
            Files.writeString(rootTextDir.resolve(chapFilename), content);
            
            // Save another file content.txt inside the folder as requested by the user
            Files.writeString(srcTextDir.resolve("content.txt"), content);
            Files.writeString(targetTextDir.resolve("content.txt"), content);
            Files.writeString(rootTextDir.resolve("content.txt"), content);
            
        } catch (IOException e) {
            System.err.println("Error synchronizing chapter files: " + e.getMessage());
        }
    }

    // GET all reviews for Admin/Owner dashboard
    @GetMapping("/reviews")
    public ResponseEntity<?> getAllReviews(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        return ResponseEntity.ok(reviewRepository.findAll());
    }

    // DELETE a review
    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<?> deleteReview(@PathVariable Long id, HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        if (!reviewRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Review not found."));
        }
        reviewRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // GET all notifications for Admin/Owner dashboard
    @GetMapping("/notifications")
    public ResponseEntity<?> getAllNotifications(HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        return ResponseEntity.ok(notificationRepository.findByOrderByCreatedAtDesc());
    }

    // Toggle read/unread status of notification
    @PutMapping("/notifications/{id}/toggle-read")
    public ResponseEntity<?> toggleNotificationRead(@PathVariable Long id, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        java.util.Optional<Notification> notifOpt = notificationRepository.findById(id);
        if (notifOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Notification not found."));
        }
        Notification notification = notifOpt.get();
        notification.setRead(!notification.isRead());
        notificationRepository.save(notification);
        return ResponseEntity.ok(Map.of("success", true, "isRead", notification.isRead()));
    }

    // DELETE a notification
    @DeleteMapping("/notifications/{id}")
    public ResponseEntity<?> deleteNotification(@PathVariable Long id, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        if (!notificationRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Notification not found."));
        }
        notificationRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // GET credentials settings
    @GetMapping("/credentials")
    public ResponseEntity<?> getCredentials(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        // Return current credentials from DB
        java.util.Map<String, String> creds = new java.util.HashMap<>();
        creds.put("googleClientId", systemSettingRepository.findById("google.client_id").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("googleClientSecret", systemSettingRepository.findById("google.client_secret").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("googleEnabled", systemSettingRepository.findById("google.enabled").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("true"));
        creds.put("discordClientId", systemSettingRepository.findById("discord.client_id").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("discordClientSecret", systemSettingRepository.findById("discord.client_secret").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("discordEnabled", systemSettingRepository.findById("discord.enabled").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("true"));
        creds.put("mailHost", systemSettingRepository.findById("mail.host").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("mailPort", systemSettingRepository.findById("mail.port").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("mailUsername", systemSettingRepository.findById("mail.username").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("mailPassword", systemSettingRepository.findById("mail.password").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("mailSender", systemSettingRepository.findById("mail.sender").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("mailRecipient", systemSettingRepository.findById("mail.recipient").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("appBaseUrl", systemSettingRepository.findById("app.base_url").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));
        creds.put("payuEnabled", "false");
        creds.put("payuMerchantKey", "");
        creds.put("payuMerchantSalt", "");
        creds.put("payuMode", "test");
        creds.put("razorpayEnabled", systemSettingRepository.findById("razorpay.enabled").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(String.valueOf(paymentService.isRazorpayEnabled())));
        creds.put("razorpayKeyId", systemSettingRepository.findById("razorpay.key.id").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(paymentService.getRazorpayKeyId()));
        creds.put("razorpayKeySecret", systemSettingRepository.findById("razorpay.key.secret").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(paymentService.getRazorpayKeySecret()));
        creds.put("razorpayMode", systemSettingRepository.findById("razorpay.mode").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(paymentService.getRazorpayMode()));
        creds.put("signupNotificationEnabled", systemSettingRepository.findById("signup.notification.enabled").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("false"));
        creds.put("signupNotificationEmail", systemSettingRepository.findById("signup.notification.email").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse(""));

        return ResponseEntity.ok(creds);
    }

    // POST credentials settings
    @PostMapping("/credentials")
    public ResponseEntity<?> saveCredentials(
            @RequestParam(required = false) String googleClientId,
            @RequestParam(required = false) String googleClientSecret,
            @RequestParam(required = false) String googleEnabled,
            @RequestParam(required = false) String discordClientId,
            @RequestParam(required = false) String discordClientSecret,
            @RequestParam(required = false) String discordEnabled,
            @RequestParam(required = false) String mailHost,
            @RequestParam(required = false) String mailPort,
            @RequestParam(required = false) String mailUsername,
            @RequestParam(required = false) String mailPassword,
            @RequestParam(required = false) String mailSender,
            @RequestParam(required = false) String mailRecipient,
            @RequestParam(required = false) String appBaseUrl,
            @RequestParam(required = false) String payuEnabled,
            @RequestParam(required = false) String payuMerchantKey,
            @RequestParam(required = false) String payuMerchantSalt,
            @RequestParam(required = false) String payuMode,
            @RequestParam(required = false) String razorpayEnabled,
            @RequestParam(required = false) String razorpayKeyId,
            @RequestParam(required = false) String razorpayKeySecret,
            @RequestParam(required = false) String razorpayMode,
            @RequestParam(required = false) String signupNotificationEnabled,
            @RequestParam(required = false) String signupNotificationEmail,
            HttpSession session) {
        
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        if (googleClientId != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("google.client_id", googleClientId.trim()));
        if (googleClientSecret != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("google.client_secret", googleClientSecret.trim()));
        if (googleEnabled != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("google.enabled", googleEnabled.trim()));
        if (discordClientId != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("discord.client_id", discordClientId.trim()));
        if (discordClientSecret != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("discord.client_secret", discordClientSecret.trim()));
        if (discordEnabled != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("discord.enabled", discordEnabled.trim()));
        if (mailHost != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.host", mailHost.trim()));
        if (mailPort != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.port", mailPort.trim()));
        if (mailUsername != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.username", mailUsername.trim()));
        if (mailPassword != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.password", mailPassword.trim()));
        if (mailSender != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.sender", mailSender.trim()));
        if (mailRecipient != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("mail.recipient", mailRecipient.trim()));
        if (appBaseUrl != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("app.base_url", appBaseUrl.trim()));
        if (razorpayEnabled != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("razorpay.enabled", razorpayEnabled.trim()));
        if (razorpayKeyId != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("razorpay.key.id", razorpayKeyId.trim()));
        if (razorpayKeySecret != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("razorpay.key.secret", razorpayKeySecret.trim()));
        if (razorpayMode != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("razorpay.mode", razorpayMode.trim()));
        if (signupNotificationEnabled != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("signup.notification.enabled", signupNotificationEnabled.trim()));
        if (signupNotificationEmail != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("signup.notification.email", signupNotificationEmail.trim()));

        return ResponseEntity.ok(Map.of("success", true));
    }

    // GET UI settings
    @GetMapping("/ui")
    public ResponseEntity<?> getUiSettings(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        java.util.Map<String, String> settings = new java.util.HashMap<>();
        settings.put("uiTemplate", systemSettingRepository.findById("ui.template").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("modern"));
        settings.put("uiTheme", systemSettingRepository.findById("ui.theme").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("midnight"));
        settings.put("uiColorPrimary", systemSettingRepository.findById("ui.color.primary").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("#5e63b6"));
        settings.put("uiColorBg", systemSettingRepository.findById("ui.color.bg").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("#0f0f1a"));
        settings.put("uiColorCard", systemSettingRepository.findById("ui.color.card").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("#181829"));
        settings.put("uiFontPrimary", systemSettingRepository.findById("ui.font.primary").map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue).orElse("Poppins"));

        return ResponseEntity.ok(settings);
    }

    // POST UI settings
    @PostMapping("/ui")
    public ResponseEntity<?> saveUiSettings(
            @RequestParam(required = false) String uiTemplate,
            @RequestParam(required = false) String uiTheme,
            @RequestParam(required = false) String uiColorPrimary,
            @RequestParam(required = false) String uiColorBg,
            @RequestParam(required = false) String uiColorCard,
            @RequestParam(required = false) String uiFontPrimary,
            HttpSession session) {
        
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        if (uiTemplate != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.template", uiTemplate.trim()));
        if (uiTheme != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.theme", uiTheme.trim()));
        if (uiColorPrimary != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.color.primary", uiColorPrimary.trim()));
        if (uiColorBg != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.color.bg", uiColorBg.trim()));
        if (uiColorCard != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.color.card", uiColorCard.trim()));
        if (uiFontPrimary != null) systemSettingRepository.save(new com.reader.Novel.Reader.model.SystemSetting("ui.font.primary", uiFontPrimary.trim()));

        return ResponseEntity.ok(Map.of("success", true));
    }

    // POST /sendmail admin email dispatch
    @PostMapping("/sendmail")
    public ResponseEntity<?> sendAdminEmail(
            @RequestParam String recipientMode,
            @RequestParam(required = false) List<String> selectedEmails,
            @RequestParam(required = false) String customEmails,
            @RequestParam String subject,
            @RequestParam String body,
            HttpSession session) {

        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied. Only admins or owners can send emails."));
        }

        if (subject == null || subject.trim().isEmpty() || body == null || body.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Subject and message body are required."));
        }

        java.util.Set<String> recipients = new java.util.HashSet<>();
        if ("select".equals(recipientMode)) {
            if (selectedEmails != null) {
                for (String email : selectedEmails) {
                    if (email != null && !email.trim().isEmpty()) {
                        recipients.add(email.trim());
                    }
                }
            }
        } else if ("custom".equals(recipientMode)) {
            if (customEmails != null) {
                String[] parts = customEmails.split(",");
                for (String part : parts) {
                    if (part != null && !part.trim().isEmpty()) {
                        recipients.add(part.trim());
                    }
                }
            }
        }

        if (recipients.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid recipients specified."));
        }

        // Dispatch emails asynchronously
        for (String email : recipients) {
            emailService.sendCustomEmailAsync(email, subject.trim(), body.trim());
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Email dispatch initiated successfully for " + recipients.size() + " recipient(s)."));
    }

    // --- Coupon Management API ---
    @GetMapping("/coupons")
    public ResponseEntity<?> getAllCoupons(HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null || (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }
        return ResponseEntity.ok(couponRepository.findAllByOrderByIdAsc());
    }

    @PostMapping("/coupons")
    public ResponseEntity<?> saveCoupon(
            @RequestParam(required = false) Long id,
            @RequestParam String code,
            @RequestParam Double discountPercentage,
            @RequestParam(required = false) String assignedUserEmail,
            @RequestParam(required = false) Boolean active,
            HttpSession session) {
        
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null || (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }

        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Coupon code is required."));
        }
        if (discountPercentage == null || discountPercentage < 0 || discountPercentage > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Discount percentage must be between 0 and 100."));
        }

        String cleanCode = code.toUpperCase().trim();
        String cleanEmail = (assignedUserEmail != null && !assignedUserEmail.trim().isEmpty()) ? assignedUserEmail.trim() : null;
        boolean cleanActive = (active != null) ? active : true;

        Coupon coupon;
        if (id != null) {
            coupon = couponRepository.findById(id).orElse(new Coupon());
            // Check uniqueness if code is changed
            if (!cleanCode.equals(coupon.getCode())) {
                if (couponRepository.findByCodeIgnoreCase(cleanCode).isPresent()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Coupon code already exists."));
                }
            }
        } else {
            if (couponRepository.findByCodeIgnoreCase(cleanCode).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Coupon code already exists."));
            }
            coupon = new Coupon();
        }

        coupon.setCode(cleanCode);
        coupon.setDiscountPercentage(discountPercentage);
        coupon.setAssignedUserEmail(cleanEmail);
        coupon.setActive(cleanActive);

        Coupon saved = couponRepository.save(coupon);
        return ResponseEntity.ok(Map.of("success", true, "coupon", saved, "message", "Coupon saved successfully."));
    }

    @PostMapping("/coupons/{couponId}/toggle")
    public ResponseEntity<?> toggleCoupon(@PathVariable Long couponId, HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null || (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }

        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Coupon not found."));
        }

        coupon.setActive(!coupon.getActive());
        Coupon saved = couponRepository.save(coupon);
        return ResponseEntity.ok(Map.of("success", true, "active", saved.getActive(), "message", "Coupon state updated."));
    }

    @DeleteMapping("/coupons/{couponId}")
    public ResponseEntity<?> deleteCoupon(@PathVariable Long couponId, HttpSession session) {
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null || (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }

        if (!couponRepository.existsById(couponId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Coupon not found."));
        }

        couponRepository.deleteById(couponId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Coupon deleted successfully."));
    }


    // -------------------------------------------------------------
    // 7. BATCH IMPORT STORIES & CHAPTERS (EPUB, TXT, ZIP & SERVER DIR)
    // -------------------------------------------------------------

    @PostMapping(value = "/stories/batch-import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> batchImportStory(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long targetNovelId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String coverUrl,
            @RequestParam(defaultValue = "NOVEL") String type,
            @RequestParam(defaultValue = "Fantasy, Adventure") String genre,
            @RequestParam(defaultValue = "0") Integer pricePerChapter,
            @RequestParam(defaultValue = "auto") String splitPattern,
            HttpSession session) {

        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not logged in."));
        }
        String role = loggedInUser.getUser_type();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role) && !"PROOFREADER".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please provide a valid file to import."));
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "imported_file.txt";
        String lowerName = originalFilename.toLowerCase();

        try {
            Novel targetNovel = null;
            if (targetNovelId != null && targetNovelId > 0) {
                targetNovel = novelService.getNovelById(targetNovelId);
                if (targetNovel == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Target novel not found."));
                }
            }

            List<Map<String, Object>> parsedChapters = new java.util.ArrayList<>();
            String extractedTitle = title;
            String extractedAuthor = author;
            String extractedDescription = description;
            String extractedCoverUrl = coverUrl;

            if (lowerName.endsWith(".epub")) {
                Map<String, Object> epubResult = parseEpubArchive(file.getInputStream());
                if (extractedTitle == null || extractedTitle.trim().isEmpty()) {
                    extractedTitle = (String) epubResult.get("title");
                }
                if (extractedAuthor == null || extractedAuthor.trim().isEmpty()) {
                    extractedAuthor = (String) epubResult.get("author");
                }
                if (extractedDescription == null || extractedDescription.trim().isEmpty()) {
                    extractedDescription = (String) epubResult.get("description");
                }
                if (extractedCoverUrl == null || extractedCoverUrl.trim().isEmpty()) {
                    extractedCoverUrl = (String) epubResult.get("coverUrl");
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> chaps = (List<Map<String, Object>>) epubResult.get("chapters");
                if (chaps != null) {
                    parsedChapters.addAll(chaps);
                }
            } else if (lowerName.endsWith(".zip")) {
                Map<String, Object> zipResult = parseZipArchive(file.getInputStream(), originalFilename);
                if (extractedTitle == null || extractedTitle.trim().isEmpty()) {
                    extractedTitle = (String) zipResult.get("title");
                }
                if (extractedAuthor == null || extractedAuthor.trim().isEmpty()) {
                    extractedAuthor = (String) zipResult.get("author");
                }
                if (extractedCoverUrl == null || extractedCoverUrl.trim().isEmpty()) {
                    extractedCoverUrl = (String) zipResult.get("coverUrl");
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> chaps = (List<Map<String, Object>>) zipResult.get("chapters");
                if (chaps != null) {
                    parsedChapters.addAll(chaps);
                }
            } else {
                String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> txtResult = parseTextChapters(text, originalFilename, splitPattern);
                if (extractedTitle == null || extractedTitle.trim().isEmpty()) {
                    extractedTitle = (String) txtResult.get("title");
                }
                if (extractedAuthor == null || extractedAuthor.trim().isEmpty()) {
                    extractedAuthor = (String) txtResult.get("author");
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> chaps = (List<Map<String, Object>>) txtResult.get("chapters");
                if (chaps != null) {
                    parsedChapters.addAll(chaps);
                }
            }

            if (parsedChapters.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No readable chapters could be found or parsed from the file."));
            }

            if (extractedTitle == null || extractedTitle.trim().isEmpty()) {
                extractedTitle = originalFilename.contains(".") ? originalFilename.substring(0, originalFilename.lastIndexOf(".")) : originalFilename;
            }
            if (extractedAuthor == null || extractedAuthor.trim().isEmpty()) {
                extractedAuthor = "Unknown Author";
            }
            if (extractedDescription == null || extractedDescription.trim().isEmpty()) {
                extractedDescription = "Imported into Yuki Tales from " + originalFilename + ".";
            }
            if (extractedCoverUrl == null || extractedCoverUrl.trim().isEmpty()) {
                extractedCoverUrl = "/uploads/cover_solo_leveling.jpg";
            }

            if (targetNovel == null) {
                targetNovel = new Novel(null, extractedTitle.trim(), extractedAuthor.trim(), extractedDescription.trim(), extractedCoverUrl.trim(), type.toUpperCase(), genre.trim(), 4.8, "ONGOING");
                targetNovel.setCreatorId(loggedInUser.getId());
                targetNovel.setYear(java.time.Year.now().getValue());
                targetNovel.setSource("Imported File");
                targetNovel = novelService.saveNovel(targetNovel);
                syncNovelFoldersAndFiles(targetNovel);
            }

            double currentMaxNumber = 0.0;
            List<Chapter> existingChapters = novelService.getChaptersByNovelId(targetNovel.getId());
            if (existingChapters != null && !existingChapters.isEmpty()) {
                for (Chapter c : existingChapters) {
                    if (c.getChapterNumber() != null && c.getChapterNumber() > currentMaxNumber) {
                        currentMaxNumber = c.getChapterNumber();
                    }
                }
            }

            int importedCount = 0;
            for (Map<String, Object> chMap : parsedChapters) {
                currentMaxNumber += 1.0;
                String chTitle = (String) chMap.get("title");
                if (chTitle == null || chTitle.trim().isEmpty()) {
                    chTitle = "Chapter " + (int) currentMaxNumber;
                }
                String chContent = (String) chMap.get("content");
                if (chContent == null || chContent.trim().isEmpty()) {
                    continue;
                }
                Chapter ch = new Chapter(null, targetNovel, chTitle.trim(), currentMaxNumber, chContent.trim(), pricePerChapter);
                ch.setPublishAt(java.time.LocalDateTime.now());
                ch.setPublishNotificationSent(true);
                Chapter savedCh = chapterServiceHelper(ch);
                syncChapterFiles(savedCh);
                importedCount++;
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "novelId", targetNovel.getId(),
                "novelTitle", targetNovel.getTitle(),
                "chapterCount", importedCount,
                "message", "Successfully imported " + importedCount + " chapter(s) into '" + targetNovel.getTitle() + "'!"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }

    @PostMapping("/stories/import-server-dir")
    public ResponseEntity<?> importServerDir(
            @RequestParam(required = false) String directoryPath,
            HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null || (!"ADMIN".equals(loggedInUser.getUser_type()) && !"OWNER".equals(loggedInUser.getUser_type()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized access."));
        }

        String userDir = System.getProperty("user.dir");
        Path targetDir = directoryPath != null && !directoryPath.trim().isEmpty() 
            ? Paths.get(directoryPath.trim()) 
            : Paths.get(userDir, "import");

        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            try {
                Files.createDirectories(targetDir);
            } catch (Exception e) {}
            return ResponseEntity.ok(Map.of("success", true, "importedCount", 0, "message", "Import directory initialized at: " + targetDir.toAbsolutePath().toString() + ". Place .epub or .txt files inside and run import again."));
        }

        int totalNovels = 0;
        int totalChapters = 0;
        List<String> importedTitles = new java.util.ArrayList<>();

        try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            Path importedDir = targetDir.resolve("imported");
            Files.createDirectories(importedDir);

            for (Path p : files) {
                String fName = p.getFileName().toString();
                String lower = fName.toLowerCase();
                if (lower.endsWith(".epub") || lower.endsWith(".txt") || lower.endsWith(".zip")) {
                    byte[] data = Files.readAllBytes(p);
                    Map<String, Object> parseRes;
                    if (lower.endsWith(".epub")) {
                        parseRes = parseEpubArchive(new java.io.ByteArrayInputStream(data));
                    } else if (lower.endsWith(".zip")) {
                        parseRes = parseZipArchive(new java.io.ByteArrayInputStream(data), fName);
                    } else {
                        parseRes = parseTextChapters(new String(data, java.nio.charset.StandardCharsets.UTF_8), fName, "auto");
                    }

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> chaps = (List<Map<String, Object>>) parseRes.get("chapters");
                    if (chaps != null && !chaps.isEmpty()) {
                        String t = (String) parseRes.getOrDefault("title", fName);
                        String a = (String) parseRes.getOrDefault("author", "Unknown Author");
                        String d = (String) parseRes.getOrDefault("description", "Imported from server directory.");
                        String c = (String) parseRes.getOrDefault("coverUrl", "/uploads/cover_solo_leveling.jpg");

                        Novel nov = new Novel(null, t, a, d, c, "NOVEL", "Fantasy, Adventure", 4.8, "ONGOING");
                        nov.setCreatorId(loggedInUser.getId());
                        nov.setYear(java.time.Year.now().getValue());
                        nov.setSource("Server Import");
                        nov = novelService.saveNovel(nov);
                        syncNovelFoldersAndFiles(nov);

                        double cNum = 1.0;
                        for (Map<String, Object> cMap : chaps) {
                            String chTitle = (String) cMap.get("title");
                            String chContent = (String) cMap.get("content");
                            if (chContent != null && !chContent.trim().isEmpty()) {
                                Chapter chapter = new Chapter(null, nov, chTitle, cNum, chContent, 0);
                                chapter.setPublishAt(java.time.LocalDateTime.now());
                                chapter.setPublishNotificationSent(true);
                                Chapter savedCh = chapterServiceHelper(chapter);
                                syncChapterFiles(savedCh);
                                cNum += 1.0;
                                totalChapters++;
                            }
                        }
                        totalNovels++;
                        importedTitles.add(t);

                        Files.move(p, importedDir.resolve(fName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Error scanning directory: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "novelsCount", totalNovels,
            "chaptersCount", totalChapters,
            "importedTitles", importedTitles,
            "message", "Successfully imported " + totalNovels + " novel(s) and " + totalChapters + " chapter(s) from server import directory!"
        ));
    }

    private Map<String, Object> parseTextChapters(String text, String filename, String splitPattern) {
        Map<String, Object> result = new java.util.HashMap<>();
        List<Map<String, Object>> chapters = new java.util.ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            result.put("chapters", chapters);
            return result;
        }

        String baseName = filename != null && filename.contains(".") ? filename.substring(0, filename.lastIndexOf(".")) : "Novel";
        result.put("title", baseName);
        result.put("author", "Unknown Author");

        String[] lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        java.util.regex.Pattern chapterRegex = java.util.regex.Pattern.compile("(?i)^\\s*(?:chapter|ch\\.?|act|episode|volume\\s+\\d+\\s+chapter|prologue|epilogue)\\s*([0-9]+(?:\\.[0-9]+)?)?(?:\\s*[:\\-\\.]?\\s*(.*))?$");
        java.util.regex.Pattern markdownHeader = java.util.regex.Pattern.compile("^\\s*#{1,3}\\s+(.*)$");

        String currentTitle = "Prologue";
        StringBuilder currentContent = new StringBuilder();
        int chapterIdx = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            java.util.regex.Matcher m = chapterRegex.matcher(trimmed);
            java.util.regex.Matcher md = markdownHeader.matcher(trimmed);

            boolean isHeader = false;
            String headerTitle = null;

            if (m.matches()) {
                isHeader = true;
                headerTitle = trimmed;
            } else if (md.matches() && md.group(1).toLowerCase().contains("chapter")) {
                isHeader = true;
                headerTitle = md.group(1).trim();
            }

            if (isHeader) {
                if (currentContent.length() > 0) {
                    chapters.add(Map.of("title", currentTitle, "content", currentContent.toString()));
                    currentContent = new StringBuilder();
                }
                currentTitle = headerTitle != null ? headerTitle : ("Chapter " + chapterIdx);
                chapterIdx++;
            } else {
                currentContent.append("<p>").append(escapeHtml(trimmed)).append("</p>\n");
            }
        }

        if (currentContent.length() > 0) {
            chapters.add(Map.of("title", currentTitle, "content", currentContent.toString()));
        }

        if (chapters.isEmpty() && lines.length > 0) {
            StringBuilder chunk = new StringBuilder();
            int count = 0;
            int cNum = 1;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                chunk.append("<p>").append(escapeHtml(trimmed)).append("</p>\n");
                count++;
                if (count >= 50) {
                    chapters.add(Map.of("title", "Chapter " + cNum, "content", chunk.toString()));
                    chunk = new StringBuilder();
                    count = 0;
                    cNum++;
                }
            }
            if (chunk.length() > 0) {
                chapters.add(Map.of("title", "Chapter " + cNum, "content", chunk.toString()));
            }
        }

        result.put("chapters", chapters);
        return result;
    }

    private Map<String, Object> parseEpubArchive(java.io.InputStream inputStream) throws Exception {
        Map<String, Object> result = new java.util.HashMap<>();
        List<Map<String, Object>> chapters = new java.util.ArrayList<>();
        Map<String, byte[]> files = new java.util.HashMap<>();

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(inputStream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    files.put(entry.getName().replace("\\", "/"), baos.toByteArray());
                }
                zis.closeEntry();
            }
        }

        String opfPath = "OEBPS/content.opf";
        if (files.containsKey("META-INF/container.xml")) {
            String containerXml = new String(files.get("META-INF/container.xml"), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("full-path=\"([^\"]+)\"").matcher(containerXml);
            if (m.find()) {
                opfPath = m.group(1);
            }
        }

        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf("/") + 1) : "";
        byte[] opfBytes = files.get(opfPath);
        if (opfBytes == null) {
            for (String key : files.keySet()) {
                if (key.endsWith(".opf")) {
                    opfBytes = files.get(key);
                    opfDir = key.contains("/") ? key.substring(0, key.lastIndexOf("/") + 1) : "";
                    break;
                }
            }
        }

        String title = "Imported EPUB Novel";
        String author = "Unknown Author";
        String description = "";
        String coverUrl = null;

        List<String> spineItemRefs = new java.util.ArrayList<>();
        Map<String, String> manifest = new java.util.HashMap<>();

        if (opfBytes != null) {
            String opf = new String(opfBytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            if (titleMatcher.find()) title = titleMatcher.group(1).trim();

            java.util.regex.Matcher authorMatcher = java.util.regex.Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            if (authorMatcher.find()) author = authorMatcher.group(1).trim();

            java.util.regex.Matcher descMatcher = java.util.regex.Pattern.compile("<dc:description[^>]*>([^<]+)</dc:description>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            if (descMatcher.find()) description = descMatcher.group(1).trim();

            java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile("<item[^>]+id=\"([^\"]+)\"[^>]+href=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            while (itemMatcher.find()) {
                manifest.put(itemMatcher.group(1), itemMatcher.group(2));
            }
            java.util.regex.Matcher itemMatcher2 = java.util.regex.Pattern.compile("<item[^>]+href=\"([^\"]+)\"[^>]+id=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            while (itemMatcher2.find()) {
                manifest.put(itemMatcher2.group(2), itemMatcher2.group(1));
            }

            java.util.regex.Matcher spineMatcher = java.util.regex.Pattern.compile("<itemref[^>]+idref=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            while (spineMatcher.find()) {
                spineItemRefs.add(spineMatcher.group(1));
            }
        }

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String k = entry.getKey().toLowerCase();
            if (k.contains("cover") && (k.endsWith(".jpg") || k.endsWith(".jpeg") || k.endsWith(".png") || k.endsWith(".webp"))) {
                String ext = k.substring(k.lastIndexOf("."));
                String uniqueName = "cover_" + UUID.randomUUID().toString() + ext;
                File uploadsDir = new File(System.getProperty("user.dir"), "uploads");
                uploadsDir.mkdirs();
                Files.write(new File(uploadsDir, uniqueName).toPath(), entry.getValue());
                coverUrl = "/uploads/" + uniqueName;
                break;
            }
        }

        int chapIndex = 1;
        for (String idref : spineItemRefs) {
            String href = manifest.get(idref);
            if (href == null) continue;
            String cleanHref = href.contains("#") ? href.substring(0, href.indexOf("#")) : href;
            String fullPath = opfDir + cleanHref;
            byte[] docBytes = files.get(fullPath);
            if (docBytes == null) {
                for (String k : files.keySet()) {
                    if (k.endsWith(cleanHref)) {
                        docBytes = files.get(k);
                        break;
                    }
                }
            }

            if (docBytes != null) {
                String docHtml = new String(docBytes, java.nio.charset.StandardCharsets.UTF_8);
                String chapTitle = "Chapter " + chapIndex;
                java.util.regex.Matcher hMatcher = java.util.regex.Pattern.compile("<h[1-3][^>]*>([^<]+)</h[1-3]>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(docHtml);
                if (hMatcher.find()) {
                    chapTitle = hMatcher.group(1).trim();
                }

                String bodyContent = docHtml;
                java.util.regex.Matcher bodyMatcher = java.util.regex.Pattern.compile("<body[^>]*>(.*?)</body>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(docHtml);
                if (bodyMatcher.find()) {
                    bodyContent = bodyMatcher.group(1);
                }

                bodyContent = bodyContent.replaceAll("(?is)<script.*?</script>", "")
                                         .replaceAll("(?is)<style.*?</style>", "");

                String textOnly = bodyContent.replaceAll("<[^>]+>", "").trim();
                if (textOnly.length() > 50) {
                    chapters.add(Map.of("title", chapTitle, "content", bodyContent.trim()));
                    chapIndex++;
                }
            }
        }

        result.put("title", title);
        result.put("author", author);
        result.put("description", description);
        result.put("coverUrl", coverUrl);
        result.put("chapters", chapters);
        return result;
    }

    private Map<String, Object> parseZipArchive(java.io.InputStream inputStream, String filename) throws Exception {
        Map<String, Object> result = new java.util.HashMap<>();
        List<Map<String, Object>> chapters = new java.util.ArrayList<>();
        Map<String, byte[]> textFiles = new java.util.TreeMap<>();
        String coverUrl = null;

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(inputStream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = entry.getName().replace("\\", "/");
                    String lower = name.toLowerCase();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    byte[] bytes = baos.toByteArray();

                    if (lower.contains("cover") && (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp"))) {
                        String ext = lower.substring(lower.lastIndexOf("."));
                        String uniqueName = "cover_" + UUID.randomUUID().toString() + ext;
                        File uploadsDir = new File(System.getProperty("user.dir"), "uploads");
                        uploadsDir.mkdirs();
                        Files.write(new File(uploadsDir, uniqueName).toPath(), bytes);
                        coverUrl = "/uploads/" + uniqueName;
                    } else if (lower.endsWith(".txt") || lower.endsWith(".html") || lower.endsWith(".xhtml") || lower.endsWith(".md")) {
                        textFiles.put(name, bytes);
                    }
                }
                zis.closeEntry();
            }
        }

        int cNum = 1;
        for (Map.Entry<String, byte[]> entry : textFiles.entrySet()) {
            String path = entry.getKey();
            String fName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
            String chapTitle = fName.contains(".") ? fName.substring(0, fName.lastIndexOf(".")) : fName;
            String text = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);

            String contentHtml = text;
            if (!fName.toLowerCase().endsWith(".html") && !fName.toLowerCase().endsWith(".xhtml")) {
                StringBuilder sb = new StringBuilder();
                for (String line : text.replace("\r\n", "\n").split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        sb.append("<p>").append(escapeHtml(t)).append("</p>\n");
                    }
                }
                contentHtml = sb.toString();
            }
            if (!contentHtml.trim().isEmpty()) {
                chapters.add(Map.of("title", chapTitle, "content", contentHtml));
                cNum++;
            }
        }

        String baseName = filename.contains(".") ? filename.substring(0, filename.lastIndexOf(".")) : filename;
        result.put("title", baseName);
        result.put("author", "Unknown Author");
        result.put("coverUrl", coverUrl);
        result.put("chapters", chapters);
        return result;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

}
