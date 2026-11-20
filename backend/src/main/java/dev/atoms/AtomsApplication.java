package dev.atoms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动 Spring Boot 应用并扫描项目中的组件。
 *
 * <p>示例：<code>AtomsApplication.main(new String[0])</code>。</p>
 */
@SpringBootApplication
public class AtomsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtomsApplication.class, args);
    }
}
