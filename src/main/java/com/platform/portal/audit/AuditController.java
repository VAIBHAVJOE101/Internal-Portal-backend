package com.platform.portal.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.platform.portal.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public PageResult<AuditService.AuditEntryDto> search(@RequestParam(required = false) String username,
                                                         @RequestParam(required = false) String action,
                                                         @RequestParam(required = false) String targetType,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) Instant from,
                                                         @RequestParam(required = false) Instant to,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return auditService.search(username, action, targetType, q, from, to, page, size);
    }

    @GetMapping("/facets")
    public Map<String, List<String>> facets() {
        return auditService.facets();
    }
}
