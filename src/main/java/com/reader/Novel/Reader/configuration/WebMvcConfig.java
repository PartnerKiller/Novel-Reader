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
                .excludePathPatterns("/static/**", "/css/**", "/js/**", "/scripts/**", "/uploads/**", "/favicon.ico", "/favicon.png", "/favicon.svg", "/api/realtime/**");
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
                .addResourceLocations("file:" + uploadsPath, "classpath:/static/covers/")
                .setCachePeriod(31536000); // 1 year cache period for cover images

        registry.addResourceHandler("/static/**", "/css/**", "/js/**", "/scripts/**", "/favicon.ico", "/favicon.png", "/favicon.svg")
                .addResourceLocations("classpath:/static/", "classpath:/static/css/", "classpath:/static/js/", "classpath:/static/scripts/")
                .setCachePeriod(86400); // 24 hours cache period for style sheets and scripts
    }
}
