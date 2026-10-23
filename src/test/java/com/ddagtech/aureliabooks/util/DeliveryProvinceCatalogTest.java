package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.entity.ShippingAddress.EconomicRegion;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryProvinceCatalogTest {

    @Test
    void contains34UniqueProvincesAndCompleteShippingZones() {
        var provinces = DeliveryProvinceCatalog.all();
        assertEquals(34, provinces.size());
        assertEquals(34L, provinces.stream().map(DeliveryProvinceCatalog.Province::code).distinct().count());
        assertEquals(34L, provinces.stream().map(DeliveryProvinceCatalog.Province::name).distinct().count());
        var counts = provinces.stream().collect(Collectors.groupingBy(DeliveryProvinceCatalog.Province::region, Collectors.counting()));
        assertEquals(Map.of(EconomicRegion.NORTHERN, 15L, EconomicRegion.CENTRAL_HIGHLANDS, 11L,
                EconomicRegion.SOUTHERN_MEKONG, 8L), counts);
    }

    @Test
    void usesCurrentProvinceCodesAndNeverGuessesMergedLegacyNames() {
        assertEquals("01", DeliveryProvinceCatalog.codeForStoredName("Hà Nội"));
        assertEquals("46", DeliveryProvinceCatalog.codeForStoredName("TP Huế"));
        assertEquals("79", DeliveryProvinceCatalog.codeForStoredName("Thành phố Hồ Chí Minh"));
        assertEquals("", DeliveryProvinceCatalog.codeForStoredName("Bình Dương"));
        assertTrue(DeliveryProvinceCatalog.findByCode("74").isEmpty());
        assertTrue(DeliveryProvinceCatalog.findByCode(null).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> DeliveryProvinceCatalog.all().clear());
    }
}
