package org.example.config;

import org.example.security.ApiSecurityInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web MVC 配置
 * 配置跨域和静态资源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiSecurityInterceptor apiSecurityInterceptor;
    private final ApiSecurityProperties apiSecurityProperties;

    @Autowired
    public WebMvcConfig(ApiSecurityInterceptor apiSecurityInterceptor, ApiSecurityProperties apiSecurityProperties) {
        this.apiSecurityInterceptor = apiSecurityInterceptor;
        this.apiSecurityProperties = apiSecurityProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowedOrigins = apiSecurityProperties.getAllowedOriginArray();
        if (allowedOrigins.length == 0) {
            allowedOrigins = new String[]{"*"};
        }

        if (Arrays.asList(allowedOrigins).contains("*")) {
            registry.addMapping("/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(false)
                    .maxAge(3600);
        } else {
            registry.addMapping("/**")
                    .allowedOrigins(allowedOrigins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiSecurityInterceptor)
                .addPathPatterns("/api/**", "/milvus/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源映射
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
