package com.example.aiknowledge.config;

import com.example.aiknowledge.mapper.DocumentMapper;
import com.example.aiknowledge.service.DocumentMigrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.documents.migration")
public class DocumentMigrationCommand implements ApplicationRunner {
    private final DocumentMapper documents;
    private final DocumentMigrationService migration;
    private final ConfigurableApplicationContext context;
    private final String mode;
    public DocumentMigrationCommand(DocumentMapper documents,DocumentMigrationService migration,
            ConfigurableApplicationContext context,@Value("${app.documents.migration}") String mode) {
        this.documents=documents; this.migration=migration; this.context=context; this.mode=mode;
    }
    @Override public void run(ApplicationArguments args) {
        if (!mode.equals("preview") && !mode.equals("apply")) throw new IllegalArgumentException("Migration must be preview or apply");
        int ready=0, failed=0;
        for (long id:documents.localDocumentIds()) {
            try {
                if (migration.migrate(id,mode.equals("apply"))) {
                    ready++;
                    System.out.println("Document " + id + (mode.equals("apply") ? ": migrated; local copy retained" : ": local file readable; ready"));
                }
            } catch (RuntimeException error) {
                failed++;
                System.err.println("Document " + id + ": failed; original record retained. Check source directory and MinIO.");
            }
        }
        System.out.printf("Migration %s: ready/success=%d, failed=%d%n",mode,ready,failed);
        final int code=failed==0?0:1;
        System.exit(SpringApplication.exit(context,()->code));
    }
}
