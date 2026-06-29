package com.reader.Novel.Reader.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @org.springframework.beans.factory.annotation.Autowired
    private UserStatusInterceptor userStatusInterceptor;

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(userStatusInterceptor)
                .excludePathPatterns("/static/**", "/css/**", "/js/**", "/scripts/**", "/uploads/**", "/favicon.ico", "/api/realtime/**");
    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String userDir = System.getProperty("user.dir");
        
        // Ensure absolute paths using forward slashes for cross-platform compatibility
        String uploadsPath = userDir.replace('\\', '/') + "/uploads/";
        File uploadsDir = new File(uploadsPath);
        if (!uploadsDir.exists()) {
            uploadsDir.mkdirs();
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsPath);
    }
}
