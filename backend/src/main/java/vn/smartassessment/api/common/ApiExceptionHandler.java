package vn.smartassessment.api.common;

import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.identity.dto.AuthDtos;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Dữ liệu gửi lên không hợp lệ.");
        return ResponseEntity.badRequest().body(new AuthDtos.ErrorResponse(message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new AuthDtos.ErrorResponse(exception.getReason()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> handleConflict(DataIntegrityViolationException exception) {
        if (isUniqueConstraintViolation(exception)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AuthDtos.ErrorResponse("Email hoặc username đã được sử dụng."));
        }
        throw exception;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> handleCredentials(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthDtos.ErrorResponse("Tên đăng nhập/email hoặc mật khẩu không chính xác."));
    }

    private boolean isUniqueConstraintViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlError && "23505".equals(sqlError.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
