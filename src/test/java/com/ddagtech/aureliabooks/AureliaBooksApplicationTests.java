package com.ddagtech.aureliabooks;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires running MySQL container or active database profile")
class AureliaBooksApplicationTests {

    @Test
    void contextLoads() {
    }

}
