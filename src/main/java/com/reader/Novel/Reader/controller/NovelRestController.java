package com.reader.Novel.Reader.controller;

import com.reader.Novel.Reader.model.Novel;
import com.reader.Novel.Reader.model.Bookmark;
import com.reader.Novel.Reader.model.User;
import com.reader.Novel.Reader.model.FlakePurchase;
import com.reader.Novel.Reader.model.FlakePackage;
import com.reader.Novel.Reader.repository.FlakePurchaseRepository;
import com.reader.Novel.Reader.service.NovelService;

import com.reader.Novel.Reader.model.Coupon;
import com.reader.Novel.Reader.repository.CouponRepository;
import com.reader.Novel.Reader.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api")
public class NovelRestController {

    @Autowired
    private NovelService novelService;

    @Autowired
    private com.reader.Novel.Reader.service.UserService userService;

    @Autowired
    private FlakePurchaseRepository flakePurchaseRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CouponRepository couponRepository;

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Autowired
    private com.reader.Novel.Reader.repository.SystemSettingRepository systemSettingRepository;



    @GetMapping("/novels")
    public List<Novel> getNovels(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String editor,
            HttpSession session) {
        
        if (isRestricted(session)) {
            return java.util.Collections.emptyList();
        }
        
        List<Novel> results;
        if (search != null && !search.trim().isEmpty()) {
            results = novelService.searchNovels(search.trim());
        } else {
            results = novelService.getAllNovels();
        }
        
        if (type != null && !type.trim().isEmpty() && !"ALL".equalsIgnoreCase(type)) {
            results = results.stream()
                .filter(n -> type.equalsIgnoreCase(n.getType()))
                .toList();
        }
        
        if (genre != null && !genre.trim().isEmpty() && !"ALL".equalsIgnoreCase(genre)) {
            results = results.stream()
                .filter(n -> n.getGenre() != null && java.util.Arrays.stream(n.getGenre().split(","))
                    .map(String::trim)
                    .anyMatch(g -> genre.equalsIgnoreCase(g)))
                .toList();
        }
        
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            results = results.stream()
                .filter(n -> status.equalsIgnoreCase(n.getStatus()))
                .toList();
        }

        if (year != null && !year.trim().isEmpty() && !"ALL".equalsIgnoreCase(year)) {
            try {
                int yVal = Integer.parseInt(year.trim());
                results = results.stream()
                    .filter(n -> n.getYear() != null && n.getYear().equals(yVal))
                    .toList();
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        if (tags != null && !tags.trim().isEmpty() && !"ALL".equalsIgnoreCase(tags)) {
            results = results.stream()
                .filter(n -> n.getTags() != null && java.util.Arrays.stream(n.getTags().split(","))
                    .map(String::trim)
                    .anyMatch(t -> tags.equalsIgnoreCase(t)))
                .toList();
        }

        if (country != null && !country.trim().isEmpty() && !"ALL".equalsIgnoreCase(country)) {
            results = results.stream()
                .filter(n -> country.equalsIgnoreCase(n.getCountryOfOrigin()))
                .toList();
        }

        if (source != null && !source.trim().isEmpty() && !"ALL".equalsIgnoreCase(source)) {
            results = results.stream()
                .filter(n -> source.equalsIgnoreCase(n.getSource()))
                .toList();
        }

        if (editor != null && !editor.trim().isEmpty() && !"ALL".equalsIgnoreCase(editor)) {
            try {
                Long editorId = Long.parseLong(editor.trim());
                results = results.stream()
                    .filter(n -> n.getCreatorId() != null && n.getCreatorId().equals(editorId))
                    .toList();
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        // Sorting
        String activeSort = (sort != null && !sort.trim().isEmpty()) ? sort.trim() : "POPULARITY";
        if ("TITLE".equalsIgnoreCase(activeSort)) {
            results = results.stream()
                .sorted((n1, n2) -> {
                    String t1 = n1.getTitle() != null ? n1.getTitle() : "";
                    String t2 = n2.getTitle() != null ? n2.getTitle() : "";
                    return t1.compareToIgnoreCase(t2);
                })
                .toList();
        } else if ("RATING".equalsIgnoreCase(activeSort)) {
            results = results.stream()
                .sorted((n1, n2) -> {
                    double r1 = n1.getRating() != null ? n1.getRating() : 0.0;
                    double r2 = n2.getRating() != null ? n2.getRating() : 0.0;
                    return Double.compare(r2, r1);
                })
                .toList();
        } else if ("NEWEST".equalsIgnoreCase(activeSort)) {
            results = results.stream()
                .sorted((n1, n2) -> {
                    Long id1 = n1.getId() != null ? n1.getId() : 0L;
                    Long id2 = n2.getId() != null ? n2.getId() : 0L;
                    return Long.compare(id2, id1);
                })
                .toList();
        } else {
            // Default "POPULARITY" (rating desc)
            results = results.stream()
                .sorted((n1, n2) -> {
                    double r1 = n1.getRating() != null ? n1.getRating() : 0.0;
                    double r2 = n2.getRating() != null ? n2.getRating() : 0.0;
                    return Double.compare(r2, r1);
                })
                .toList();
        }
        
        return results;
    }

    private boolean isRestricted(HttpSession session) {
        if (novelService.isSecuredMode()) {
            User loggedInUser = (User) session.getAttribute("user");
            return loggedInUser == null || !"OWNER".equals(loggedInUser.getUser_type());
        }
        return false;
    }

    @PostMapping("/bookmarks/toggle")
    public ResponseEntity<?> toggleBookmark(@RequestParam Long novelId, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }
        
        Bookmark bookmark = novelService.toggleBookmark(loggedInUser.getId(), novelId);
        boolean isBookmarked = (bookmark != null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("bookmarked", isBookmarked);
        response.put("reading", isBookmarked && bookmark.getLastReadChapterNumber() > 0.0);
        response.put("message", isBookmarked ? "Added to bookshelf" : "Removed from bookshelf");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bookmarks/progress")
    public ResponseEntity<?> updateProgress(@RequestParam Long novelId, @RequestParam Double chapterNumber, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }
        
        Bookmark bookmark = novelService.updateBookmarkProgress(loggedInUser.getId(), novelId, chapterNumber);
        Double progressVal = bookmark != null ? bookmark.getLastReadChapterNumber() : chapterNumber;
        return ResponseEntity.ok(Map.of("success", true, "progress", progressVal));
    }

    @PostMapping("/chapters/{chapterId}/purchase")
    public ResponseEntity<?> purchaseChapter(@PathVariable Long chapterId, HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }

        com.reader.Novel.Reader.model.Chapter chapter = novelService.getChapterById(chapterId);
        if (chapter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Chapter not found."));
        }

        if (chapter.getPrice() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "This chapter is free."));
        }

        // Check if user already purchased
        if (novelService.hasPurchased(loggedInUser.getId(), chapterId)) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Chapter already purchased."));
        }

        try {
            novelService.executePurchase(loggedInUser.getId(), chapterId, chapter.getPrice());
            
            User updatedUser = userService.getUserById(loggedInUser.getId());
            loggedInUser.setBalance(updatedUser.getBalance());
            session.setAttribute("user", loggedInUser);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Chapter unlocked successfully!",
                "newBalance", updatedUser.getBalance()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Transaction failed: " + e.getMessage()));
        }
    }

    @PostMapping("/novels/{novelId}/rate")
    public ResponseEntity<?> rateNovel(
            @PathVariable Long novelId,
            @RequestParam Integer ratingValue,
            HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }

        if (ratingValue == null || ratingValue < 1 || ratingValue > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rating must be between 1 and 5."));
        }

        Novel novel = novelService.getNovelById(novelId);
        if (novel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Story not found."));
        }

        novelService.submitRating(loggedInUser.getId(), novelId, ratingValue);
        Novel updated = novelService.getNovelById(novelId);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Thank you for rating!",
            "newRating", updated.getRating()
        ));
    }

    @GetMapping("/user/validate-coupon")
    public ResponseEntity<?> validateCoupon(
            @RequestParam String code,
            @RequestParam Integer amount,
            HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }

        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Coupon code is required."));
        }
        if (amount == null || amount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid purchase amount."));
        }

        String cleanCode = code.toUpperCase().trim();
        java.util.Optional<Coupon> couponOpt = couponRepository.findByCodeIgnoreCase(cleanCode);
        if (couponOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid coupon code."));
        }

        Coupon coupon = couponOpt.get();
        if (!coupon.getActive()) {
            return ResponseEntity.badRequest().body(Map.of("error", "This coupon is inactive."));
        }

        User dbUser = userService.getUserById(loggedInUser.getId());
        if (dbUser.getUsedCoupons().contains(coupon.getCode().toUpperCase().trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "You have already used this coupon."));
        }

        // Check user restriction: email OR name
        if (coupon.getAssignedUserEmail() != null && !coupon.getAssignedUserEmail().isEmpty()) {
            String restricted = coupon.getAssignedUserEmail().trim().toLowerCase();
            String userEmail = loggedInUser.getEmail() != null ? loggedInUser.getEmail().trim().toLowerCase() : "";
            String userName = loggedInUser.getName() != null ? loggedInUser.getName().trim().toLowerCase() : "";
            if (!restricted.equals(userEmail) && !restricted.equals(userName)) {
                return ResponseEntity.badRequest().body(Map.of("error", "This coupon is not valid for your account."));
            }
        }

        // Calculate original price based on package
        List<FlakePackage> packages = novelService.getAllFlakePackages();
        double originalPrice = 0.0;
        if (packages == null || packages.isEmpty()) {
            originalPrice = amount * 0.01;
        } else {
            FlakePackage applicablePack = packages.get(0);
            for (FlakePackage pack : packages) {
                if (pack.getAmount() <= amount) {
                    applicablePack = pack;
                }
            }
            double rate = applicablePack.getPrice() / applicablePack.getAmount();
            originalPrice = amount * rate;
        }

        double discount = coupon.getDiscountPercentage();
        double discountedPrice = originalPrice * (1.0 - (discount / 100.0));

        return ResponseEntity.ok(Map.of(
            "valid", true,
            "code", coupon.getCode(),
            "discountPercentage", discount,
            "originalPrice", originalPrice,
            "discountedPrice", discountedPrice
        ));
    }

    @PostMapping("/user/purchase-flakes")
    public ResponseEntity<?> purchaseFlakes(
            @RequestParam Integer amount,
            @RequestParam(required = false) String gateway,
            @RequestParam(required = false) String couponCode,
            HttpSession session) {
        if (isRestricted(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Platform is in secured mode."));
        }
        User loggedInUser = (User) session.getAttribute("user");
        if (loggedInUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please login first."));
        }
        
        if (amount == null || amount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid purchase amount."));
        }
        
        User user = userService.getUserById(loggedInUser.getId());
        if (user.getId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }
        
        // Calculate standard price based on standard/custom package pricing
        List<FlakePackage> packages = novelService.getAllFlakePackages();
        double price = 0.0;
        if (packages == null || packages.isEmpty()) {
            price = amount * 0.01;
        } else {
            FlakePackage applicablePack = packages.get(0);
            for (FlakePackage pack : packages) {
                if (pack.getAmount() <= amount) {
                    applicablePack = pack;
                }
            }
            double rate = applicablePack.getPrice() / applicablePack.getAmount();
            price = amount * rate;
        }

        // Validate and apply coupon if present
        double discountPercent = 0.0;
        String cleanCoupon = null;
        if (couponCode != null && !couponCode.trim().isEmpty()) {
            cleanCoupon = couponCode.toUpperCase().trim();
            java.util.Optional<Coupon> couponOpt = couponRepository.findByCodeIgnoreCase(cleanCoupon);
            if (couponOpt.isPresent()) {
                Coupon coupon = couponOpt.get();
                if (coupon.getActive()) {
                    if (user.getUsedCoupons().contains(cleanCoupon)) {
                        return ResponseEntity.badRequest().body(Map.of("error", "You have already used this coupon."));
                    }
                    boolean restrictedMatched = true;
                    if (coupon.getAssignedUserEmail() != null && !coupon.getAssignedUserEmail().isEmpty()) {
                        String restricted = coupon.getAssignedUserEmail().trim().toLowerCase();
                        String userEmail = user.getEmail() != null ? user.getEmail().trim().toLowerCase() : "";
                        String userName = user.getName() != null ? user.getName().trim().toLowerCase() : "";
                        if (!restricted.equals(userEmail) && !restricted.equals(userName)) {
                            restrictedMatched = false;
                        }
                    }
                    if (restrictedMatched) {
                        discountPercent = coupon.getDiscountPercentage();
                    } else {
                        return ResponseEntity.badRequest().body(Map.of("error", "This coupon is not valid for your account."));
                    }
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "This coupon is inactive."));
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid coupon code."));
            }
        }

        if (discountPercent > 0.0) {
            price = price * (1.0 - (discountPercent / 100.0));
        }

        // Determine active gateway
        String activeGateway = (gateway != null) ? gateway.toLowerCase() : "";
        if (activeGateway.isEmpty()) {
            activeGateway = paymentService.isPayUEnabled() ? "payu" : "mock";
        }

        if ("payu".equals(activeGateway) && paymentService.isPayUEnabled()) {
            String txnid = "txn_" + System.currentTimeMillis();
            // Convert USD price to INR assuming 1 USD = 83 INR
            double priceInInr = price * 83.0;
            String productinfo = "Purchase " + amount + " Snow Flakes";
            String firstname = user.getName() != null && !user.getName().trim().isEmpty() ? user.getName().trim() : "Reader";
            String email = user.getEmail() != null && !user.getEmail().trim().isEmpty() ? user.getEmail().trim() : "reader@yukitales.com";
            
            // Build callback URLs
            String dynamicBaseUrl = systemSettingRepository.findById("app.base_url")
                .map(com.reader.Novel.Reader.model.SystemSetting::getSettingValue)
                .orElse(appBaseUrl);
            String cleanBaseUrl = dynamicBaseUrl != null ? dynamicBaseUrl.trim() : "";
            if (cleanBaseUrl.isEmpty() || "/".equals(cleanBaseUrl)) {
                cleanBaseUrl = "http://localhost:8080";
            }
            if (!cleanBaseUrl.toLowerCase().startsWith("http://") && !cleanBaseUrl.toLowerCase().startsWith("https://")) {
                cleanBaseUrl = "http://" + cleanBaseUrl;
            }
            if (cleanBaseUrl.endsWith("/")) {
                cleanBaseUrl = cleanBaseUrl.substring(0, cleanBaseUrl.length() - 1);
            }
            String surl = cleanBaseUrl + "/api/payment/payu/success";
            String furl = cleanBaseUrl + "/api/payment/payu/failure";
            
            // Generate the checkout hash with udf4 as couponCode
            String hash = paymentService.generatePaymentHash(
                txnid, priceInInr, productinfo, firstname, email,
                String.valueOf(user.getId()), String.valueOf(amount), String.valueOf(price),
                cleanCoupon != null ? cleanCoupon : ""
            );
            
            Map<String, Object> responseMap = new java.util.HashMap<>();
            responseMap.put("success", true);
            responseMap.put("payu", true);
            responseMap.put("key", paymentService.getPayUMerchantKey());
            responseMap.put("txnid", txnid);
            responseMap.put("amount", String.format(java.util.Locale.US, "%.2f", priceInInr));
            responseMap.put("productinfo", productinfo);
            responseMap.put("firstname", firstname);
            responseMap.put("email", email);
            responseMap.put("phone", "9999999999");
            responseMap.put("surl", surl);
            responseMap.put("furl", furl);
            responseMap.put("hash", hash);
            responseMap.put("service_provider", "payu_paisa");
            responseMap.put("actionUrl", paymentService.getPayUActionUrl());
            responseMap.put("udf1", String.valueOf(user.getId()));
            responseMap.put("udf2", String.valueOf(amount));
            responseMap.put("udf3", String.valueOf(price));
            responseMap.put("udf4", cleanCoupon != null ? cleanCoupon : "");
            
            return ResponseEntity.ok(responseMap);
        }

        // Mock Checkout Fallback
        user.setBalance((user.getBalance() != null ? user.getBalance() : 0) + amount);
        if (cleanCoupon != null) {
            user.getUsedCoupons().add(cleanCoupon);
        }
        userService.updateUser(user);
        
        // Save the flake purchase record
        FlakePurchase flakePurchase = new FlakePurchase();
        flakePurchase.setUserId(user.getId());
        flakePurchase.setAmount(amount);
        flakePurchase.setPrice(price);
        flakePurchase.setCouponCode(cleanCoupon);
        flakePurchase.setPurchasedAt(java.time.LocalDateTime.now());
        flakePurchaseRepository.save(flakePurchase);

        loggedInUser.setBalance(user.getBalance());
        session.setAttribute("user", loggedInUser);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "newBalance", user.getBalance(),
            "message", "Successfully purchased " + amount + " Snow Flakes!"
        ));
    }
}
