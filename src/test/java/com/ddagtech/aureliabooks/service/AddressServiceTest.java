package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.AddressServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AddressServiceTest {
    jakarta.validation.ValidatorFactory validatorFactory;
    ShippingAddressRepository addresses; UserRepository users; AddressOrderGuard orders;
    AddressServiceImpl service; List<ShippingAddress> all;
    AddressRequest request=new AddressRequest("Nguyễn An","0912345678",ShippingAddress.EconomicRegion.NORTHERN,"Hà Nội","Ba Đình","Đội Cấn","12 Đội Cấn",false);
    @BeforeEach void setup() {
        addresses=mock(ShippingAddressRepository.class); users=mock(UserRepository.class); orders=mock(AddressOrderGuard.class);
        var role=new Role();role.setRoleName("ROLE_CUSTOMER");
        var owner=User.builder().id(1L).isActive(true).role(role).build();
        when(users.findOwnerForUpdate(1L)).thenReturn(Optional.of(owner));
        all=new ArrayList<>(); when(addresses.findByUserIdAndIsActiveTrueOrderByIdAsc(1L)).thenAnswer(i->all.stream().filter(a->Boolean.TRUE.equals(a.getIsActive())).toList());
        when(addresses.findByIdAndUserIdAndIsActiveTrue(anyLong(),eq(1L))).thenAnswer(i->all.stream().filter(a->a.getId().equals(i.getArgument(0))).findFirst());
        validatorFactory=Validation.buildDefaultValidatorFactory();
        service=new AddressServiceImpl(addresses,users,orders,validatorFactory.getValidator());
    }
    @AfterEach void closeValidatorFactory() {
        if (validatorFactory != null) validatorFactory.close();
    }
    ShippingAddress address(long id,boolean def) {var a=new ShippingAddress();a.setId(id);a.setIsDefault(def);all.add(a);return a;}
    @Test void sixthAddressRejectedBeforeAnyWrite() {
        for(int i=1;i<=5;i++) address(i,i==1);
        assertThrows(AppException.class,()->service.create(1L,request)); verify(addresses,never()).saveAllAndFlush(any());
    }
    @Test void mutationsCannotAccessAnotherOwnersAddress() {
        assertThrows(AppException.class,()->service.update(1L,99L,request));
        assertThrows(AppException.class,()->service.delete(1L,99L));
        assertThrows(AppException.class,()->service.setDefault(1L,99L));
        verify(orders,never()).hasUnfinishedOrder(any(),any());
    }
    @Test void settingDefaultRemovesPreviousDefault() {
        var a=address(1,true); var b=address(2,false);service.setDefault(1L,2L);
        assertFalse(a.getIsDefault());assertTrue(b.getIsDefault());
    }
    @Test void activeOrderPreventsDeletion() {
        var a=address(1,true);when(orders.hasUnfinishedOrder(1L,1L)).thenReturn(true);
        assertThrows(AppException.class,()->service.delete(1L,1L));assertTrue(a.getIsActive());
    }
    @Test void deletingDefaultPromotesRemainingAddressAndSoftDeletes() {
        var a=address(1,true);var b=address(2,false);service.delete(1L,1L);
        assertFalse(a.getIsActive());assertFalse(a.getIsDefault());assertTrue(b.getIsDefault());
    }
    @Test void firstAddressIsDefaultEvenIfCheckboxNotSelected() {
        when(addresses.saveAllAndFlush(any())).thenAnswer(i->{List<ShippingAddress> values=i.getArgument(0);var a=values.get(0);a.setId(1L);assertTrue(a.getIsDefault());return values;});
        assertEquals(1L,service.create(1L,request));
    }
}
