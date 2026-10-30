package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Order;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusSchemaTest {
    @ParameterizedTest
    @ValueSource(strings = {"schema.sql", "docker/initdb/01_schema.sql", "docker/migrations/20261030_order_returned_status.sql"})
    void freshAndUpgradeDefinitionsAcceptEveryPersistedOrderStatus(String source) throws Exception {
        var matcher = Pattern.compile("order_status\\s+ENUM\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(Files.readString(Path.of(source)));
        assertThat(matcher.find()).as("order_status definition in %s", source).isTrue();
        var statuses = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.strip().replace("'", "")).toList();
        assertThat(statuses).containsExactlyElementsOf(Arrays.stream(Order.OrderStatus.values()).map(Enum::name).toList());
    }
}
