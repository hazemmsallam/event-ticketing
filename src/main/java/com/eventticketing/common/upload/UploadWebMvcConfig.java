package com.eventticketing.common.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves uploaded files back at {@code /uploads/**} from the on-disk directory
 * {@code app.uploads.directory}. CORS mirrors the API so an admin on a different port can preview
 * what it just uploaded.
 */
@Configuration
public class UploadWebMvcConfig implements WebMvcConfigurer {

    private final String directory;

    public UploadWebMvcConfig(@Value("${app.uploads.directory:./uploads}") String directory) {
        this.directory = directory;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + root.toString().replace('\\', '/') + "/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/uploads/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET");
    }
}
