package com.ddagtech.aureliabooks.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.ddagtech.aureliabooks.exception.AppException;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class AvatarStorageTest {
    @TempDir Path directory;
    @Test void rejectsOversizedAndFakeImage() {
        var storage=new AvatarStorage(directory.toString());
        assertThrows(AppException.class,()->storage.store(new MockMultipartFile("avatar","a.png","image/png",new byte[2*1024*1024+1])));
        assertThrows(AppException.class,()->storage.store(new MockMultipartFile("avatar","a.png","image/png","<script/>".getBytes())));
        assertThrows(AppException.class,()->storage.read("../../passwords"));
    }
    @Test void validPngIsReencodedAndOriginalFilenameIgnored() throws Exception {
        var storage=new AvatarStorage(directory.toString());var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        String url=storage.store(new MockMultipartFile("avatar","../../attack.png","image/png",bytes.toByteArray()));
        assertTrue(url.startsWith("/profile/avatar/"));assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(storage.read(url.substring(16)))));
        storage.delete(url);assertThrows(AppException.class,()->storage.read(url.substring(16)));
    }
    @Test void exactlyTwoMiBValidPngAcceptedAndResized() throws Exception {
        var storage=new AvatarStorage(directory.toString());var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1000,1000,BufferedImage.TYPE_INT_RGB),"png",bytes);
        byte[] padded=java.util.Arrays.copyOf(bytes.toByteArray(),2*1024*1024);
        String url=storage.store(new MockMultipartFile("avatar","image.png","image/png",padded));
        var image=ImageIO.read(new java.io.ByteArrayInputStream(storage.read(url.substring(16))));
        assertEquals(512,image.getWidth());assertEquals(512,image.getHeight());
    }
}
