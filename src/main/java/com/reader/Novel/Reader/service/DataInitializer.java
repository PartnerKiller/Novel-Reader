package com.reader.Novel.Reader.service;

import com.reader.Novel.Reader.model.Novel;
import com.reader.Novel.Reader.model.Chapter;
import com.reader.Novel.Reader.model.User;
import com.reader.Novel.Reader.repository.NovelRepository;
import com.reader.Novel.Reader.repository.ChapterRepository;
import com.reader.Novel.Reader.repository.UserRepository;
import com.reader.Novel.Reader.model.FlakePackage;
import com.reader.Novel.Reader.repository.FlakePackageRepository;
import com.reader.Novel.Reader.util.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FlakePackageRepository flakePackageRepository;

    @Override
    public void run(String... args) throws Exception {
        String userHome = System.getProperty("user.home");
        boolean isLocalDesktop = userHome != null && userHome.contains("sayan");

        if (flakePackageRepository.count() == 0) {
            flakePackageRepository.save(new FlakePackage(null, 100, 0.99));
            flakePackageRepository.save(new FlakePackage(null, 500, 3.99));
            flakePackageRepository.save(new FlakePackage(null, 1000, 6.99));
            flakePackageRepository.save(new FlakePackage(null, 2000, 11.99));
        }

        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY = FALSE");
        try {
            
            // Ensure username column exists
            jdbcTemplate.execute("ALTER TABLE reader_internal ADD COLUMN IF NOT EXISTS username VARCHAR(255)");

            // De-duplicate users (by email and username)
            java.util.List<java.util.Map<String, Object>> allUsers = jdbcTemplate.queryForList("SELECT id, email, username FROM reader_internal ORDER BY id ASC");
            java.util.Set<String> seenEmails = new java.util.HashSet<>();
            java.util.Set<String> seenUsernames = new java.util.HashSet<>();
            for (java.util.Map<String, Object> uMap : allUsers) {
                Long id = ((Number) uMap.get("id")).longValue();
                String email = (String) uMap.get("email");
                String username = (String) uMap.get("username");
                
                boolean duplicate = false;
                if (email != null) {
                    String emailClean = email.trim().toLowerCase();
                    if (seenEmails.contains(emailClean)) {
                        duplicate = true;
                    } else {
                        seenEmails.add(emailClean);
                    }
                }
                if (username != null) {
                    String usernameClean = username.trim().toLowerCase();
                    if (seenUsernames.contains(usernameClean)) {
                        duplicate = true;
                    } else {
                        seenUsernames.add(usernameClean);
                    }
                }
                
                if (duplicate) {
                    System.out.println("Deleting duplicate user ID " + id + " (email: " + email + ", username: " + username + ")");
                    jdbcTemplate.update("DELETE FROM bookmarks WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM ratings WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM comments WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM purchases WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM flake_purchases WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM reviews WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM user_used_coupons WHERE user_id = ?", id);
                    jdbcTemplate.update("DELETE FROM reader_internal WHERE id = ?", id);
                }
            }

            // Apply unique constraints to prevent future duplicates
            jdbcTemplate.execute("ALTER TABLE reader_internal ADD CONSTRAINT IF NOT EXISTS UC_email UNIQUE(email)");
            jdbcTemplate.execute("ALTER TABLE reader_internal ADD CONSTRAINT IF NOT EXISTS UC_username UNIQUE(username)");
            
            // Ensure comments table has reports_count column
            jdbcTemplate.execute("ALTER TABLE comments ADD COLUMN IF NOT EXISTS reports_count INT DEFAULT 0 NOT NULL");

            // Ensure notifications table allows NULL comment_id for chapter publish updates
            jdbcTemplate.execute("ALTER TABLE notifications ALTER COLUMN comment_id DROP NOT NULL");

            // Re-map any references of the legacy editor (ID 2) to the admin (ID 1)
            jdbcTemplate.update("UPDATE novels SET creator_id = 1 WHERE creator_id = 2");
            jdbcTemplate.update("DELETE FROM bookmarks WHERE user_id = 2 AND novel_id IN (SELECT novel_id FROM bookmarks WHERE user_id = 1)");
            jdbcTemplate.update("UPDATE bookmarks SET user_id = 1 WHERE user_id = 2");
            jdbcTemplate.update("DELETE FROM purchases WHERE user_id = 2 AND chapter_id IN (SELECT chapter_id FROM purchases WHERE user_id = 1)");
            jdbcTemplate.update("UPDATE purchases SET user_id = 1 WHERE user_id = 2");
            jdbcTemplate.update("DELETE FROM ratings WHERE user_id = 2 AND novel_id IN (SELECT novel_id FROM ratings WHERE user_id = 1)");
            jdbcTemplate.update("UPDATE ratings SET user_id = 1 WHERE user_id = 2");

            // Clean up Slice Of Life capitalization inconsistency
            jdbcTemplate.update("UPDATE novels SET genre = REPLACE(genre, 'Slice Of Life', 'Slice of Life')");

            // Delete legacy users
            jdbcTemplate.execute("DELETE FROM reader_internal WHERE email IN ('sakura@sakura.com', 'editor@yuki.com')");

            // Safe migration of any regular users occupying system IDs (0, 1, 3)
            for (Long sysId : new Long[]{0L, 1L, 3L}) {
                String sysEmail = sysId == 0L ? "sakura" : (sysId == 1L ? "admin" : "editor");
                String sysFullEmail = sysId == 0L ? "sakura@yukitales.com" : (sysId == 1L ? "admin@yukitales.com" : "editor@yukitales.com");
                java.util.List<java.util.Map<String, Object>> conflictingUsers = jdbcTemplate.queryForList(
                    "SELECT id, email FROM reader_internal WHERE id = ?", sysId);
                if (!conflictingUsers.isEmpty()) {
                    String email = (String) conflictingUsers.get(0).get("email");
                    if (!sysEmail.equalsIgnoreCase(email) && !sysFullEmail.equalsIgnoreCase(email)) {
                        // Conflicting regular user! Move them to a high ID
                        Long newId = 1000L + sysId;
                        while (!jdbcTemplate.queryForList("SELECT id FROM reader_internal WHERE id = ?", newId).isEmpty()) {
                            newId += 100L;
                        }
                        
                        // Update referencing tables
                        jdbcTemplate.update("UPDATE bookmarks SET user_id = ? WHERE user_id = ?", newId, sysId);
                        jdbcTemplate.update("UPDATE purchases SET user_id = ? WHERE user_id = ?", newId, sysId);
                        jdbcTemplate.update("UPDATE ratings SET user_id = ? WHERE user_id = ?", newId, sysId);
                        jdbcTemplate.update("UPDATE comments SET user_id = ? WHERE user_id = ?", newId, sysId);
                        jdbcTemplate.update("UPDATE notifications SET user_id = ? WHERE user_id = ?", newId, sysId);
                        jdbcTemplate.update("UPDATE reader_internal SET id = ? WHERE id = ?", newId, sysId);
                        System.out.println("Migrated conflicting user '" + email + "' from system ID " + sysId + " to new ID " + newId);
                    }
                }
            }

            // Restore owner/admin if they were moved to ID 1000/1001
            java.util.List<java.util.Map<String, Object>> movedOwner = jdbcTemplate.queryForList("SELECT id FROM reader_internal WHERE id = 1000 AND (email = 'sakura@yukitales.com' OR username = 'sakura')");
            if (!movedOwner.isEmpty()) {
                jdbcTemplate.update("UPDATE bookmarks SET user_id = 0 WHERE user_id = 1000");
                jdbcTemplate.update("UPDATE purchases SET user_id = 0 WHERE user_id = 1000");
                jdbcTemplate.update("UPDATE ratings SET user_id = 0 WHERE user_id = 1000");
                jdbcTemplate.update("UPDATE comments SET user_id = 0 WHERE user_id = 1000");
                jdbcTemplate.update("UPDATE notifications SET user_id = 0 WHERE user_id = 1000");
                jdbcTemplate.update("UPDATE reader_internal SET id = 0 WHERE id = 1000");
            }
            
            java.util.List<java.util.Map<String, Object>> movedAdmin = jdbcTemplate.queryForList("SELECT id FROM reader_internal WHERE id = 1001 AND (email = 'admin@yukitales.com' OR username = 'admin')");
            if (!movedAdmin.isEmpty()) {
                jdbcTemplate.update("UPDATE bookmarks SET user_id = 1 WHERE user_id = 1001");
                jdbcTemplate.update("UPDATE purchases SET user_id = 1 WHERE user_id = 1001");
                jdbcTemplate.update("UPDATE ratings SET user_id = 1 WHERE user_id = 1001");
                jdbcTemplate.update("UPDATE comments SET user_id = 1 WHERE user_id = 1001");
                jdbcTemplate.update("UPDATE notifications SET user_id = 1 WHERE user_id = 1001");
                jdbcTemplate.update("UPDATE reader_internal SET id = 1 WHERE id = 1001");
            }

            // Ensure H2 auto-increment sequence restarts at 200 to avoid future conflicts
            jdbcTemplate.execute("ALTER TABLE reader_internal ALTER COLUMN id RESTART WITH 200");

            // One-time database migration to default existing users to subscribed to updates
            java.util.List<java.util.Map<String, Object>> migrationSetting = jdbcTemplate.queryForList("SELECT setting_value FROM system_settings WHERE setting_key = 'email_migration_done'");
            if (migrationSetting.isEmpty()) {
                jdbcTemplate.execute("UPDATE reader_internal SET subscribed_to_updates = TRUE");
                jdbcTemplate.update("INSERT INTO system_settings (setting_key, setting_value) VALUES ('email_migration_done', 'true')");
                System.out.println("One-time email subscription migration executed successfully.");
            }

            // One-time username column migration
            jdbcTemplate.execute("ALTER TABLE reader_internal ADD COLUMN IF NOT EXISTS username VARCHAR(255)");
            java.util.List<java.util.Map<String, Object>> usernameMigrated = jdbcTemplate.queryForList("SELECT setting_value FROM system_settings WHERE setting_key = 'username_migration_done'");
            if (usernameMigrated.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> migrationUsers = jdbcTemplate.queryForList("SELECT id, email FROM reader_internal");
                java.util.Set<String> takenUsernames = new java.util.HashSet<>();
                for (java.util.Map<String, Object> uMap : migrationUsers) {
                    Long id = ((Number) uMap.get("id")).longValue();
                    String currentEmail = (String) uMap.get("email");
                    if (currentEmail == null || currentEmail.trim().isEmpty()) {
                        continue;
                    }
                    String baseUsername;
                    String finalEmail = currentEmail;
                    if (!currentEmail.contains("@")) {
                        baseUsername = currentEmail.trim();
                        finalEmail = baseUsername.toLowerCase() + "@yukitales.com";
                    } else {
                        baseUsername = currentEmail.split("@")[0].trim();
                    }
                    
                    String targetUsername = baseUsername;
                    int suffix = 0;
                    while (takenUsernames.contains(targetUsername.toLowerCase())) {
                        targetUsername = baseUsername + suffix;
                        suffix++;
                    }
                    takenUsernames.add(targetUsername.toLowerCase());
                    jdbcTemplate.update("UPDATE reader_internal SET username = ?, email = ? WHERE id = ?", targetUsername, finalEmail, id);
                }
                jdbcTemplate.update("INSERT INTO system_settings (setting_key, setting_value) VALUES ('username_migration_done', 'true')");
                System.out.println("One-time username database migration completed successfully.");
            }
            
            // Ensure admin exists with ID 1
            java.util.List<java.util.Map<String, Object>> admins = jdbcTemplate.queryForList("SELECT id FROM reader_internal WHERE id = 1");
            if (admins.isEmpty()) {
                String adminHashed = PasswordUtils.hashPassword("admin");
                jdbcTemplate.update("INSERT INTO reader_internal (id, name, username, email, password, user_type, balance) VALUES (1, 'Admin Admin', 'admin', 'admin@yukitales.com', ?, 'ADMIN', 100)", adminHashed);
            }



            // Create the view named READER for H2 console users
            jdbcTemplate.execute("DROP TABLE IF EXISTS READER CASCADE");
            jdbcTemplate.execute("DROP VIEW IF EXISTS READER");
            jdbcTemplate.execute("CREATE VIEW READER AS SELECT id, balance, username, email, name, password, user_type FROM reader_internal");

            // Create the H2 database user admin/admin with admin privileges
            jdbcTemplate.execute("CREATE USER IF NOT EXISTS admin PASSWORD 'admin'");
            jdbcTemplate.execute("ALTER USER admin ADMIN TRUE");
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY = TRUE");
        }

        Long targetCreatorId = 1L; // Fallback default to admin user ID 1
        if (jdbcTemplate.queryForList("SELECT id FROM reader_internal WHERE id = 0").size() > 0) {
            targetCreatorId = 0L;
        }

        // Migrate/update creatorId for all existing novels to match the creator
        for (Novel n : novelRepository.findAll()) {
            boolean updated = false;
            if (n.getCreatorId() == null || !n.getCreatorId().equals(targetCreatorId)) {
                n.setCreatorId(targetCreatorId);
                updated = true;
            }
            if (updated) {
                novelRepository.save(n);
            }
        }

        if (novelRepository.count() == 0) {
            try {
                org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("seed_data.sql");
                if (resource.exists()) {
                    org.springframework.jdbc.datasource.init.ResourceDatabasePopulator populator = 
                        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(resource);
                    populator.execute(jdbcTemplate.getDataSource());
                    System.out.println("Default catalog content successfully seeded from seed_data.sql.");
                }
            } catch (Exception e) {
                System.err.println("Error seeding default catalog from seed_data.sql: " + e.getMessage());
            }
        }

        try {
            String userDir = System.getProperty("user.dir");
            File uploadsDir = new File(userDir, "uploads");
            if (!uploadsDir.exists()) {
                uploadsDir.mkdirs();
            }
            org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver = 
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] coverResources = resolver.getResources("classpath:/static/covers/*");
            for (org.springframework.core.io.Resource r : coverResources) {
                String filename = r.getFilename();
                if (filename != null) {
                    File target = new File(uploadsDir, filename);
                    if (!target.exists()) {
                        java.nio.file.Files.copy(r.getInputStream(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error synchronizing static covers to uploads directory: " + e.getMessage());
        }

    }
}
