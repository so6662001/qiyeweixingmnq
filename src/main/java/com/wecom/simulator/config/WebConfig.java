package com.wecom.simulator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/wecom").setViewName("forward:/wecom/index.html");
        registry.addViewController("/wecom/").setViewName("forward:/wecom/index.html");
        registry.addViewController("/wechat").setViewName("forward:/wechat/index.html");
        registry.addViewController("/wechat/").setViewName("forward:/wechat/index.html");
        registry.addViewController("/mockups").setViewName("forward:/mockups/index.html");
        registry.addViewController("/mockups/").setViewName("forward:/mockups/index.html");
    }
}
