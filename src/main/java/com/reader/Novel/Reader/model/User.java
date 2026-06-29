package com.reader.Novel.Reader.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Entity
@Scope("prototype")
@Data
@Table(name = "reader_internal")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    private String password;
    private String user_type;
    private Integer balance = 100;

    private String loginType = "LOCAL";
    private String updatesEmail;
    private Boolean subscribedToUpdates = true;
    private Boolean subscribedToMentions = true;

    private Boolean banned = false;
    private java.time.LocalDateTime timeoutUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_used_coupons", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "coupon_code")
    private java.util.Set<String> usedCoupons = new java.util.HashSet<>();

    public User() {

    }

    public User(Long id, String name, String email, String password, String user_type) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.user_type = user_type;
        this.balance = 100;
    }

    public User(Long id, String name, String email, String password, String user_type, Integer balance) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.user_type = user_type;
        this.balance = balance;
    }


    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getUser_type() { return user_type; }
    public void setUser_type(String user_type) { this.user_type = user_type; }

    public Integer getBalance() { return balance; }
    public void setBalance(Integer balance) { this.balance = balance; }

    public String getLoginType() {
        return loginType != null ? loginType : "LOCAL";
    }

    public void setLoginType(String loginType) {
        this.loginType = loginType;
    }

    public String getUpdatesEmail() {
        return updatesEmail;
    }

    public void setUpdatesEmail(String updatesEmail) {
        this.updatesEmail = updatesEmail;
    }

    public Boolean getSubscribedToUpdates() {
        return subscribedToUpdates != null ? subscribedToUpdates : true;
    }

    public void setSubscribedToUpdates(Boolean subscribedToUpdates) {
        this.subscribedToUpdates = subscribedToUpdates;
    }

    public Boolean getSubscribedToMentions() {
        return subscribedToMentions != null ? subscribedToMentions : true;
    }

    public void setSubscribedToMentions(Boolean subscribedToMentions) {
        this.subscribedToMentions = subscribedToMentions;
    }

    public Boolean getBanned() { return banned != null ? banned : false; }
    public void setBanned(Boolean banned) { this.banned = banned; }

    public java.time.LocalDateTime getTimeoutUntil() { return timeoutUntil; }
    public void setTimeoutUntil(java.time.LocalDateTime timeoutUntil) { this.timeoutUntil = timeoutUntil; }

    public java.util.Set<String> getUsedCoupons() {
        if (usedCoupons == null) {
            usedCoupons = new java.util.HashSet<>();
        }
        return usedCoupons;
    }

    public void setUsedCoupons(java.util.Set<String> usedCoupons) {
        this.usedCoupons = usedCoupons;
    }

    @Override
    public String toString() {
        return "User {id=" + id + ", name='" + name + "', email='" + email + "', password='" + password + "', user_type='" + user_type + "', balance=" + balance + ", loginType='" + loginType + "', updatesEmail='" + updatesEmail + "', subscribedToUpdates=" + subscribedToUpdates + ", subscribedToMentions=" + subscribedToMentions + ", banned=" + banned + ", timeoutUntil=" + timeoutUntil + "}";
    }
}