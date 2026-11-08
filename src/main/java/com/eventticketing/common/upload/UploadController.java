package com.eventticketing.common.upload;

import com.eventticketing.common.exception.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local file upload for admin-authored event posters.
 *
 * <p>Images land in {@code app.uploads.directory} (default {@code ./uploads}) under a random name,
 * and {@link UploadWebMvcConfig} serves them back at {@code /uploads/**}. The returned URL is
 * absolute, built from the current request's base URL, so both the admin preview and the mobile
 * app can render it without extra wiring.
 *
 * <p>Localhost scope: this stores files on the server's own disk; swap for S3/object storage before
 * production. Not an attempt at a general-purpose uploader.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> ALLOWED_EXT =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    private final Path baseDir;

    public UploadController(@Value("${app.uploads.directory:./uploads}") String directory) throws IOException {
        this.baseDir = Paths.get(directory).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("Uploads directory: {}", baseDir);
    }

    @PostMapping(path = "/images")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("No file provided.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessRuleException("Image must be 10 MB or smaller.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessRuleException("Only JPEG, PNG, WebP or GIF images are accepted.");
        }
        String ext = extensionFor(file.getOriginalFilename(), contentType);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        Path target = baseDir.resolve(filename).normalize();
        if (!target.startsWith(baseDir)) {
            // Guards against a crafted filename trying to escape the uploads root.
            throw new BusinessRuleException("Invalid filename.");
        }
        file.transferTo(target);

        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/uploads/")
                .path(filename)
                .build()
                .toUriString();
        return Map.of("url", url, "filename", filename);
    }

    private static String extensionFor(String originalName, String contentType) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ALLOWED_EXT.contains(ext)) {
                    return ext.equals("jpeg") ? "jpg" : ext;
                }
            }
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
    }
}
