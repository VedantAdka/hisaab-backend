package com.vedant.hisaab.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * NEW — serves whatever lands in the local "uploads/" folder (created next
 * to wherever the app runs from) as plain static files. A receipt saved to
 * uploads/receipts/<uuid>.jpg becomes reachable at
 * http://localhost:8080/uploads/receipts/<uuid>.jpg — see UploadController.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}
