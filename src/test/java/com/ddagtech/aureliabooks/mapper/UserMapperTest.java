package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.entity.User;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    @Test
    void emptyContactsAndDobRemainCompletableWithoutExposingCredentials() {
        User user = User.builder().id(7L).fullName("Khách hàng").passwordHash("private-hash").build();
        var settings = UserMapper.toContactSettings(user);
        assertThat(settings).isEqualTo(new ContactSettingsView(true, false, false));
        assertThat(Arrays.stream(ContactSettingsView.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("passwordRequired", "emailPresent", "phonePresent");
        var profile = UserMapper.toProfile(user);
        assertThat(profile.canCompleteDob()).isTrue();
        assertThat(profile.canChangePassword()).isTrue();
        assertThat(profile.canCompletePhone()).isFalse();
        assertThat(profile.toString()).doesNotContain("private-hash");
    }

    @Test
    void savedDobAndGoogleAccountFlagsMatchExistingProfilePolicy() {
        User user = User.builder().email("google@example.test").phone("0912345678")
                .dob(LocalDate.of(2000, 1, 1)).avatarUrl("https://example.test/avatar.png").build();
        assertThat(UserMapper.toContactSettings(user)).isEqualTo(new ContactSettingsView(false, true, true));
        assertThat(UserMapper.toProfile(user).canCompleteDob()).isFalse();
        assertThat(UserMapper.toProfile(user).canChangePassword()).isFalse();
        assertThat(UserMapper.toNavbar(user).avatarUrl()).isEqualTo(user.getAvatarUrl());
    }
}
