package com.chineselearning.common.security;

import com.chineselearning.common.exception.ApiProblems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs inside Security after response headers but before CSRF reads the body. */
public final class ApiRequestGuard extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES=1_048_576;
    private final ApiProblems problems;
    private final com.chineselearning.modules.settings.GeneralSettings settings;
    public ApiRequestGuard(ApiProblems problems,com.chineselearning.modules.settings.GeneralSettings settings) { this.problems=problems;this.settings=settings; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String requestId=UUID.randomUUID().toString();
        request.setAttribute("requestId",requestId); response.setHeader("X-Request-ID",requestId);
        if(!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request,response);return; }
        String type=request.getContentType();
        if(type!=null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            problems.write(request,response,415,"Chức năng tải file chưa được mở.");return;
        }
        int limit=settings.snapshot().bodyLimitKiB()*1024;
        if(request.getContentLengthLong()>limit) { tooLarge(request,response);return; }
        // Bounded read also catches chunked requests with no Content-Length.
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit) { tooLarge(request,response);return; }
        if(body.length>0 && (type==null || !type.split(";",2)[0].strip().equalsIgnoreCase("application/json"))) {
            problems.write(request,response,415,"API chỉ nhận dữ liệu JSON.");return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var source=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return source.read(); }
                    @Override public int read(byte[] b,int off,int len) { return source.read(b,off,len); }
                    @Override public boolean isFinished() { return source.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API input only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
        },response);
    }
    private void tooLarge(HttpServletRequest request,HttpServletResponse response) throws IOException { problems.write(request,response,413,"Dữ liệu gửi lên vượt giới hạn cấu hình của hệ thống."); }
}
