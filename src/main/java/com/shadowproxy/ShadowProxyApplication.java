package com.shadowproxy;

import com.shadowproxy.config.ProxyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class ShadowProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShadowProxyApplication.class, args);
    }
}
