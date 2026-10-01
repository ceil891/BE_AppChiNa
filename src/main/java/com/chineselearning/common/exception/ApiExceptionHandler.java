package com.chineselearning.common.exception;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ApiProblems.problem(status, "Dữ liệu không hợp lệ.", path(request));
        var fields = new LinkedHashMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        problem.setProperty("errors", fields);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,Object body,HttpHeaders headers,HttpStatusCode status,WebRequest request) {
        if(body==null) body=ApiProblems.problem(status,"Yêu cầu không hợp lệ hoặc không được hỗ trợ.",path(request));
        if(body instanceof ProblemDetail problem) problem.setProperty("requestId",request.getAttribute("requestId",WebRequest.SCOPE_REQUEST));
        return super.handleExceptionInternal(ex,body,headers,status,request);
    }
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ApiProblems.problem(status, "JSON không hợp lệ hoặc có trường không được hỗ trợ.", path(request)), headers, status, request);
    }
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> authentication(AuthenticationException ex, WebRequest request) {
        return ResponseEntity.status(401).body(ApiProblems.problem(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng.", path(request)));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException ex, WebRequest request) {
        var problem=ApiProblems.problem(ex.getStatusCode(),ex.getReason(),path(request));
        if(ex instanceof FieldValidationException field)problem.setProperty("errors",java.util.Map.of(field.field(),field.getReason()));
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(problem);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, WebRequest request) {
        logger.error("Unexpected API failure requestId="+request.getAttribute("requestId",WebRequest.SCOPE_REQUEST)+" type="+ex.getClass().getName());
        return ResponseEntity.status(500).body(ApiProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR,
            "Máy chủ gặp lỗi. Vui lòng thử lại sau.", path(request)));
    }
    private String path(WebRequest request) { return ((ServletWebRequest) request).getRequest().getRequestURI(); }
}
