package com.ddagtech.aureliabooks.security;

import org.springframework.security.authentication.LockedException;

/** NFR-09 temporary lock, distinct from an administratively inactive account. */
public class TemporaryLoginLockException extends LockedException {
    public TemporaryLoginLockException() {
        super("Đăng nhập tạm thời bị khóa do thử sai nhiều lần. Vui lòng thử lại sau 15 phút.");
    }
}
