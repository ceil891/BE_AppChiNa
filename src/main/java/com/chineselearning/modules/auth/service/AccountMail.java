package com.chineselearning.modules.auth.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.net.URI;
import java.util.UUID;

@Service
public class AccountMail {
    private final String mode,base,from; private final Path directory; private final ObjectProvider<JavaMailSender> sender;
    public AccountMail(ObjectProvider<JavaMailSender> sender,@Value("${account.mail.mode:disabled}") String mode,
        @Value("${account.mail.base-url:http://127.0.0.1:5173}") String base,@Value("${account.mail.from:no-reply@example.test}") String from,
        @Value("${account.mail.local-directory:../.tools/mail}") String directory) {
        this.sender=sender;this.mode=mode;this.base=base;this.from=from;this.directory=Path.of(directory).toAbsolutePath().normalize();
        if(!java.util.Set.of("disabled","local","smtp").contains(mode))throw new IllegalArgumentException("Invalid mail mode");
        var uri=URI.create(base);
        boolean loopback=java.util.Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()==null?"":uri.getHost());
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme())))throw new IllegalArgumentException("Use a trusted HTTPS base URL");
        if(mode.equals("local") && !loopback)throw new IllegalArgumentException("Local mailbox requires a loopback base URL");
    }
    public void available() { if(mode.equals("disabled") || mode.equals("smtp") && sender.getIfAvailable()==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Dịch vụ email chưa được cấu hình."); }
    public void send(String email,String purpose,String token) {
        available();String action=purpose.equals("RESET")?"reset":"verify";
        String subject=purpose.equals("RESET")?"Đặt lại mật khẩu ChinaNN":"Xác minh email ChinaNN";
        String body=subject+"\n\nMở liên kết và xác nhận trên trang: "+base+"/account/"+action+"#token="+token+"\nLiên kết dùng một lần, hết hạn sau "+(purpose.equals("RESET")?"30 phút":"24 giờ")+". Nếu không yêu cầu, hãy bỏ qua thư này.";
        try {
            if(mode.equals("local")) { Files.createDirectories(directory);Files.writeString(directory.resolve(UUID.randomUUID()+".txt"),"To: "+email+"\nSubject: "+subject+"\n\n"+body,StandardOpenOption.CREATE_NEW); }
            else { var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject(subject);message.setText(body);sender.getObject().send(message); }
        } catch(Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa gửi được email. Hãy thử lại sau."); }
    }
}
