package com.chineselearning.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;

@Component
public class ApiProblems {
    private final ObjectMapper mapper;
    public ApiProblems(ObjectMapper mapper) { this.mapper = mapper; }
    public static ProblemDetail problem(HttpStatusCode status, String detail, String path) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setInstance(URI.create(path));
        return p;
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(problem(HttpStatusCode.valueOf(status), detail, request.getRequestURI())));
    }
}

