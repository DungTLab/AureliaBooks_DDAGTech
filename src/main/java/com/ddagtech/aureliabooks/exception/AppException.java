package com.ddagtech.aureliabooks.exception;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import lombok.Getter;

/**
 * Custom Business Runtime Exception.
 * Thrown across service layer upon business rule violations (BR-01 through BR-07, BR-21).
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AppException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }
}
