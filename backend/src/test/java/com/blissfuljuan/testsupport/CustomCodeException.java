package com.blissfuljuan.testsupport;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Test-only ApiException with a code that is not in the ErrorCode enum (uses ApiException's protected constructor). */
class CustomCodeException extends ApiException {
    CustomCodeException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
