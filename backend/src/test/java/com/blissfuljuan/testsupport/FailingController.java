package com.blissfuljuan.testsupport;

import com.blissfuljuan.die.common.exception.ApiException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Endpoints that fail in specific ways, for testing the global error handler. Deliberately OUTSIDE the
 * com.blissfuljuan.die package so component scanning never registers it; tests opt in with @Import.
 */
@RestController
@RequestMapping("/api/v1/test")
public class FailingController {

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("secret internal detail: jdbc:postgresql://10.9.8.7/die password=hunter2");
    }

    @GetMapping("/too-large")
    String tooLarge() {
        throw new MaxUploadSizeExceededException(5L * 1024 * 1024);
    }

    @GetMapping("/db-down")
    String dbDown() {
        throw new DataAccessResourceFailureException("Connection to 10.0.0.5:5432 refused");
    }

    @GetMapping("/api-error")
    String apiError() {
        throw ApiException.conflict("CUSTOM_CODE", "A custom, person-readable message.");
    }
}
