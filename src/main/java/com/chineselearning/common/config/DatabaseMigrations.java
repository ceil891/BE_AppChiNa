package com.chineselearning.common.config;
import java.nio.file.*;
import java.util.Properties;
import org.flywaydb.core.Flyway;

/** Explicit one-shot mode: no HTTP server and no application sessions. */
public final class DatabaseMigrations {
    private DatabaseMigrations() {}
    public static void run() throws Exception {
        var properties=new Properties();
        String url=System.getenv("MIGRATION_JDBC_URL"),user=System.getenv("MIGRATION_USERNAME"),password=System.getenv("MIGRATION_PASSWORD");
        boolean environment=url!=null || user!=null || password!=null;
        if(!environment) {
            try(var reader=Files.newBufferedReader(Path.of("migration-local.properties"))) { properties.load(reader); }
            url=properties.getProperty("migration.url");user=properties.getProperty("migration.user");password=properties.getProperty("migration.password");
        }
        if(url==null || user==null || password==null || password.isBlank()) throw new IllegalArgumentException("Missing migration configuration.");
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").schemas("public")
            .defaultSchema("public").cleanDisabled(true).load().migrate();
    }
}
