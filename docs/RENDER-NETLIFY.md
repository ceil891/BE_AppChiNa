# Triển khai ChinaNN: Render + Netlify

Bộ cấu hình đã chuẩn bị; chưa có dịch vụ cloud được tạo. Cần repository GitHub/GitLab của riêng dự án (không đẩy toàn ổ E:). Không đưa file local credentials, .env, database/backups, .tools, target hoặc node_modules lên Git.

## Kiến trúc
Netlify phục vụ frontend/dist. Trình duyệt gọi /api cùng origin; Netlify proxy tới Render qua HTTPS. Backend giữ cookie HttpOnly + Secure + SameSite=Lax và CSRF. Không đổi sang token trong localStorage. Phải kiểm tra Set-Cookie/JSESSIONID trên domain thật trước khi mở cho người dùng.

## 1. Tạo database và migrate
Tạo Render PostgreSQL riêng. Đặt backend và DB cùng region. Chọn gói theo nhu cầu trong dashboard; bộ cấu hình không tự đăng ký gói trả phí. Không dùng database local hoặc dữ liệu thật để thử.

Build JAR với Java 17: cd backend rồi ./mvnw -DskipTests package (kiểm thử chạy riêng trước deploy).
Chạy migration từ một môi trường tin cậy có thể kết nối DB, dùng ba biến môi trường:
- MIGRATION_JDBC_URL: jdbc:postgresql://HOST:5432/DBNAME?sslmode=require
- MIGRATION_USERNAME: tài khoản owner/migration của database mới
- MIGRATION_PASSWORD: mật khẩu tương ứng

Chạy: java -jar target/backend-0.0.1-SNAPSHOT.jar --migrate-only
Không đặt migration credentials trên web service. Dùng External URL nếu chạy từ máy cá nhân; Internal URL chỉ dùng trong mạng Render. PostgreSQL URL dạng postgresql:// không dùng trực tiếp làm JDBC URL.

Sau migration, kết nối psql bằng owner đúng DB mới rồi chạy database/scripts/render-runtime-grants.sql. Script tạo chinann_runtime với quyền từng bảng và hỏi mật khẩu bằng lệnh password của psql; không lưu mật khẩu trong source. Nếu role đã tồn tại, script dừng để rà lại quyền. Không chạy script provisioning local trên Render. Chặn truy cập ngoài DB sau khi hoàn tất các bước quản trị cần thiết.

## 2. Render Web Service
- Liên kết repository, chọn Docker.
- Root Directory: backend; Dockerfile: ./Dockerfile; Docker Context: .
- Health check: /api/v1/lessons (kiểm tra cả kết nối database).
- Một instance: HttpSession hiện nằm trong bộ nhớ, restart sẽ yêu cầu đăng nhập lại. Chưa scale nhiều instance nếu chưa có session store dùng chung.
- SPRING_PROFILES_ACTIVE=render
- DB_JDBC_URL=jdbc:postgresql://INTERNAL_HOST:5432/DBNAME?sslmode=require
- DB_USERNAME=chinann_runtime
- DB_PASSWORD: mật khẩu runtime vừa đặt
- FRONTEND_ORIGIN=https://TEN-SITE.netlify.app (chính xác origin, không dấu / cuối)
- GEMINI_API_KEY: chỉ thêm tại Render khi dùng AI
- ACCOUNT_MAIL_MODE=smtp cùng SPRING_MAIL_HOST, SPRING_MAIL_PORT, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD và ACCOUNT_MAIL_FROM nếu mở khôi phục mật khẩu qua email. Cấu hình SMTP TLS theo nhà cung cấp.

Render cấp PORT, profile render bind 0.0.0.0. Docker chỉ copy pom và src; không đưa các file secret local vào image. Không chạy profile bootstrap/test trên web service.

## 3. Netlify
Import cùng repository, dùng netlify.toml ở root. Base frontend; publish dist; command đã có sẵn. Đặt biến build RENDER_API_ORIGIN=https://TEN-BACKEND.onrender.com rồi deploy. Script sinh _redirects, đặt proxy /api trước SPA fallback; không chứa key hoặc mật khẩu. Không đặt secret database/Gemini trên Netlify.

Sau khi Netlify cấp domain, cập nhật FRONTEND_ORIGIN tại Render rồi redeploy backend. Không wildcard CORS cho deploy previews; muốn thử preview dùng backend/database staging riêng. Bật chuyển HTTP sang HTTPS trong cấu hình site và xác minh cả /api.

Proxy Netlify có timeout 26 giây. Profile render đặt Gemini countTokens 3 giây + generateContent 15 giây để chừa thời gian xử lý. Render cold start có thể vượt hạn này; nghiệm thu trên gói triển khai thực tế trước khi mở AI cho người dùng.

## 4. Dữ liệu, quản trị và nghiệm thu
Database mới chỉ có dữ liệu từ migration. Khóa học tạo tại local và tài khoản admin local không tự chuyển lên cloud. Cần tạo tài khoản quản trị bằng quy trình quản trị đã có, hoặc chuyển dữ liệu có kiểm soát từ backup; không thêm tài khoản/mật khẩu mặc định vào source. Tạo/xuất bản khóa học sau khi rà nội dung.

Kiểm tra trên domain Netlify: tải trực tiếp /courses và /lessons/1; đăng ký trả vai trò học viên; login nhận cookie Secure/HttpOnly; refresh vẫn đăng nhập; CSRF thiếu/sai bị chặn; logout thu hồi phiên; hai user không xem ghi chú của nhau; user thường không vào admin; body vượt hạn trả 413 và multipart 415. API phải no-store, không được CDN cache dữ liệu tài khoản. Kiểm tra phát âm, microphone HTTPS, gửi AI dưới timeout và trên điện thoại.

Trước mở chính thức: backup tự động + diễn tập restore, giám sát lỗi/uptime, SMTP thật, kiểm tra chi phí/giới hạn gói. Redeploy/rollback phải đi cùng kế hoạch migration; không rollback schema bằng xóa database.

## Tài liệu chính thức
- https://render.com/docs/docker
- https://render.com/docs/configure-environment-variables
- https://docs.netlify.com/manage/routing/redirects/rewrites-proxies/

Lưu ý giới hạn theo IP: hiện backend không tin forwarded headers. Sau proxy, nhiều người có thể cùng IP nguồn; cần nghiệm thu rate limit login/register dưới lưu lượng thật trước khi mở đăng ký rộng rãi. Không bật tin mọi X-Forwarded-For để xử lý tắt vấn đề này.
