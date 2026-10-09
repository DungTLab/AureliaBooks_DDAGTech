package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: requires a disposable database whose name begins uc08_qa_. */
@SpringBootTest(properties = "app.avatar.directory=./target/uc08-avatars-test")
@EnabledIfSystemProperty(named="uc08.mysql",matches="true")
class ProfileAddressMySqlTest {
    @Autowired AddressService addresses;
    @Autowired UserService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    Long owner; Long other;
    AddressRequest request(boolean def) { return new AddressRequest("Nguyễn An","0912345678",
            ShippingAddress.EconomicRegion.NORTHERN,"Hà Nội","Ba Đình","Đội Cấn","12 Đội Cấn",def); }
    @BeforeEach void setup() {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        assertNotNull(database,"A disposable QA database must be selected");
        assertTrue(database.startsWith("uc08_qa_"),"Only disposable QA database allowed");
        owner=user("owner");other=user("other");
    }
    Long user(String name) {
        String email=name+java.util.UUID.randomUUID()+"@example.com";
        jdbc.update("INSERT INTO users(email,password_hash,full_name,auth_provider,role_id) VALUES(?, '$2a$10$placeholder', 'QA Customer', 'LOCAL', (SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))",email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);
    }
    @AfterEach void cleanup() {
        if(owner==null) return;
        jdbc.update("DELETE FROM orders WHERE user_id IN (?,?)",owner,other);
        jdbc.update("DELETE FROM shipping_addresses WHERE user_id IN (?,?)",owner,other);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)",owner,other);
    }
    @Test void concurrentSixthAddressCannotExceedQuotaOrDuplicateDefault() throws Exception {
        for(int i=0;i<4;i++) addresses.create(owner,request(false));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{ready.countDown();start.await();try{addresses.create(owner,request(true));return true;}catch(AppException e){return false;}};
            var a=executor.submit(task);var b=executor.submit(task);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(5,addresses.list(owner).size());
        assertEquals(1,addresses.list(owner).stream().filter(a->a.isDefault()).count());
    }
    void order(Long address, String status) {
        jdbc.update("""
            INSERT INTO orders(order_code,user_id,shipping_address_id,shipping_recipient_name,shipping_phone,
                shipping_full_address,shipping_fee,final_total_amount,order_status,payment_method)
            VALUES(?,?,?,'QA','0912345678','Snapshot remains unchanged',0,100,?,'COD')
            """,java.util.UUID.randomUUID().toString().replace("-",""),owner,address,status);
    }
    @Test void activeOrderBlocksDeleteButCompletedOrderAllowsIt() {
        Long a=addresses.create(owner,request(false));order(a,"SHIPPING");
        assertThrows(AppException.class,()->addresses.delete(owner,a));
        jdbc.update("UPDATE orders SET order_status='DELIVERED' WHERE user_id=?",owner);
        addresses.delete(owner,a);assertTrue(addresses.list(owner).isEmpty());
        assertEquals("Snapshot remains unchanged",jdbc.queryForObject("SELECT shipping_full_address FROM orders WHERE user_id=?",String.class,owner));
    }
    @Test void crossOwnerMutationAndOrderLinkAreRejected() {
        Long foreign=addresses.create(other,request(false));
        assertThrows(AppException.class,()->addresses.delete(owner,foreign));
        assertThrows(AppException.class,()->addresses.update(owner,foreign,request(false)));
        assertThrows(AppException.class,()->addresses.setDefault(owner,foreign));
        assertThrows(DataIntegrityViolationException.class,()->order(foreign,"PENDING_PAYMENT"));
    }
    @Test void legacyUnknownOrderConservativelyProtectsAddress() {
        Long a=addresses.create(owner,request(false));order(null,"PENDING_PAYMENT");
        assertThrows(AppException.class,()->addresses.delete(owner,a));
    }
    @Test void googlePhoneCompletionPersistsOnceAndUniquePhoneIsProtected() {
        jdbc.update("UPDATE users SET auth_provider='GOOGLE',provider_id=?,password_hash=NULL WHERE id=?","sub-"+owner,owner);
        var change=new com.ddagtech.aureliabooks.dto.request.ProfileUpdateRequest("Nguyễn An","0912345678",null,com.ddagtech.aureliabooks.entity.User.Gender.MALE);
        profiles.updateProfile(owner,change);
        assertEquals("0912345678",profiles.viewProfile(owner).phone());
        assertFalse(profiles.viewProfile(owner).canCompletePhone());
        assertThrows(AppException.class,()->profiles.updateProfile(owner,new com.ddagtech.aureliabooks.dto.request.ProfileUpdateRequest(
                "Nguyễn An","0987654321",null,com.ddagtech.aureliabooks.entity.User.Gender.MALE)));
        jdbc.update("UPDATE users SET auth_provider='GOOGLE',provider_id=?,password_hash=NULL WHERE id=?","sub-"+other,other);
        assertThrows(AppException.class,()->profiles.updateProfile(other,change));
    }
    @Test void rolledBackAvatarDoesNotLeaveFileOrDatabaseReference() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("avatar","a.png","image/png",bytes.toByteArray());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            profiles.updateAvatar(owner,file);status.setRollbackOnly();
        });
        assertNull(profiles.viewProfile(owner).avatarUrl());
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of("target/uc08-avatars-test"))) {
            assertEquals(0,files.count());
        }
    }
}
