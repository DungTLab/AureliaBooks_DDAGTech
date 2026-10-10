package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.nio.file.*;
import java.io.*;
import java.util.UUID;

/** Private local storage. Decode/re-encode removes uploaded metadata and executable payloads. */
@Service
public class AvatarStorage {
    private final Path root;
    public AvatarStorage(@Value("${app.avatar.directory:./uploads/avatars}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 2L * 1024 * 1024)
            throw invalid("Ảnh đại diện phải có dung lượng từ 1 byte đến 2 MB.");
        Path target = null;
        try (var uploaded = file.getInputStream(); var input = ImageIO.createImageInputStream(uploaded)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Vui lòng chọn ảnh PNG hoặc JPEG hợp lệ.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
                        || (long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000)
                    throw invalid("Ảnh phải là PNG/JPEG và không quá 16 triệu điểm ảnh.");
                var image = reader.read(0);
                double scale = Math.min(1.0, 512.0 / Math.max(image.getWidth(), image.getHeight()));
                if (scale < 1.0) {
                    var thumbnail = new java.awt.image.BufferedImage(Math.max(1, (int)(image.getWidth() * scale)),
                            Math.max(1, (int)(image.getHeight() * scale)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = thumbnail.createGraphics();
                    try {
                        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(image, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
                    } finally { graphics.dispose(); }
                    image = thumbnail;
                }
                Files.createDirectories(root);
                String filename = UUID.randomUUID() + ".png";
                target = path(filename);
                try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
                }
                return "/profile/avatar/" + filename;
            } finally { reader.dispose(); }
        } catch (IOException e) {
            if (target != null) delete("/profile/avatar/" + target.getFileName());
            throw invalid("Không thể lưu ảnh đại diện. Vui lòng thử lại.");
        }
    }
    public byte[] read(String filename) {
        try { return Files.readAllBytes(path(filename)); }
        catch (IOException e) { throw new AppException(ErrorCode.RESOURCE_NOT_FOUND); }
    }
    public void delete(String url) {
        if (url == null || !url.startsWith("/profile/avatar/")) return;
        try { Files.deleteIfExists(path(url.substring("/profile/avatar/".length()))); }
        catch (IOException e) { /* Old orphan may be cleaned by operations; never undo committed profile. */ }
    }
    private Path path(String filename) {
        if (!filename.matches("[0-9a-f-]{36}\\.png")) throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        return root.resolve(filename);
    }
    private AppException invalid(String message) { return new AppException(ErrorCode.INVALID_INPUT_DATA, message); }
}
