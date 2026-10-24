package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.entity.ShippingAddress.EconomicRegion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Versioned province reference data. Shipping zones are store policy. */
public final class DeliveryProvinceCatalog {

    private static final List<Province> PROVINCES = load();

    private DeliveryProvinceCatalog() {}

    public record Province(String code, String name, EconomicRegion region) {}

    public static List<Province> all() {
        return PROVINCES;
    }

    public static Optional<Province> findByCode(String code) {
        return PROVINCES.stream()
            .filter(province -> province.code().equals(code))
            .findFirst();
    }

    /** Match existing names only; never guess a merged province from an old name. */
    public static String codeForStoredName(String name) {
        String normalized = normalizeName(name);
        return PROVINCES.stream()
            .filter(province -> normalizeName(province.name()).equals(normalized))
            .map(Province::code)
            .findFirst()
            .orElse("");
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.strip().toLowerCase(Locale.ROOT).replaceFirst("^(thành phố|tỉnh|tp\\.?)\\s+", "");
    }

    private static List<Province> load() {
        try (var input = DeliveryProvinceCatalog.class.getResourceAsStream("/data/vietnam-delivery-provinces.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing delivery province catalogue");
            }
            var root = new ObjectMapper().readTree(input);
            var provinces = new ArrayList<Province>();
            var codes = new HashSet<String>();
            for (var node : root.path("provinces")) {
                String code = node.path("code").asText();
                String name = node.path("name").asText();
                if (!code.matches("[0-9]{2}") || name.isBlank() || !codes.add(code)) {
                    throw new IllegalStateException("Invalid delivery province catalogue");
                }
                provinces.add(new Province(code, name, EconomicRegion.valueOf(node.path("region").asText())));
            }
            if (provinces.isEmpty()) {
                throw new IllegalStateException("Delivery province catalogue must not be empty");
            }
            return List.copyOf(provinces);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read delivery province catalogue", ex);
        }
    }
}
