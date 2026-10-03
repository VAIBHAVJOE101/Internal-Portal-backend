package com.platform.portal.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import com.platform.portal.common.PageResult;
import com.platform.portal.common.Strings;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records every mutating operation performed through the portal. Entries are written in their own
 * transaction so a failed business operation still leaves an audit trail.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final Json json;
    private final TransactionTemplate requiresNew;

    public AuditService(AuditLogRepository repository, Json json, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.json = json;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs the action and records success/failure with optional before/after details. */
    public <T> T track(String action, String targetType, String targetId, Map<String, Object> details, Supplier<T> work) {
        try {
            T result = work.get();
            record(action, targetType, targetId, details, true, null);
            return result;
        } catch (RuntimeException e) {
            record(action, targetType, targetId, details, false, e.getMessage());
            throw e;
        }
    }

    public void track(String action, String targetType, String targetId, Map<String, Object> details, Runnable work) {
        track(action, targetType, targetId, details, () -> {
            work.run();
            return null;
        });
    }

    public void record(String action, String targetType, String targetId, Map<String, Object> details, boolean success, String error) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTs(Instant.now());
            entry.setUsername(CurrentUser.username());
            entry.setUserRole(CurrentUser.role());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(Strings.truncate(targetId, 300));
            entry.setDetails(details == null || details.isEmpty() ? null : json.write(details));
            entry.setClientIp(clientIp());
            entry.setSuccess(success);
            entry.setError(Strings.truncate(error, 2000));
            requiresNew.executeWithoutResult(status -> repository.save(entry));
        } catch (RuntimeException e) {
            log.error("Failed to write audit entry {} {} {}", action, targetType, targetId, e);
        }
    }

    public PageResult<AuditEntryDto> search(String username, String action, String targetType, String q,
                                            Instant from, Instant to, int page, int size) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (!Strings.isBlank(username)) p.add(cb.equal(root.get("username"), username));
            if (!Strings.isBlank(action)) p.add(cb.equal(root.get("action"), action));
            if (!Strings.isBlank(targetType)) p.add(cb.equal(root.get("targetType"), targetType));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("ts"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("ts"), to));
            if (!Strings.isBlank(q)) {
                String like = "%" + q.toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("targetId")), like), cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("username")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var result = repository.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "ts")));
        return PageResult.of(result.map(this::toDto));
    }

    public List<AuditEntryDto> recent(int limit) {
        return repository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "ts"))).map(this::toDto).getContent();
    }

    public Map<String, List<String>> facets() {
        List<AuditLog> all = repository.findAll(PageRequest.of(0, 2000, Sort.by(Sort.Direction.DESC, "ts"))).getContent();
        Map<String, List<String>> facets = new LinkedHashMap<>();
        facets.put("users", all.stream().map(AuditLog::getUsername).distinct().sorted().toList());
        facets.put("actions", all.stream().map(AuditLog::getAction).distinct().sorted().toList());
        facets.put("targetTypes", all.stream().map(AuditLog::getTargetType).distinct().sorted().toList());
        return facets;
    }

    private AuditEntryDto toDto(AuditLog a) {
        return new AuditEntryDto(a.getId(), a.getTs(), a.getUsername(), a.getUserRole(), a.getAction(), a.getTargetType(),
                a.getTargetId(), json.read(a.getDetails()), a.getClientIp(), a.isSuccess(), a.getError());
    }

    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (!Strings.isBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record AuditEntryDto(Long id, Instant ts, String username, String role, String action, String targetType,
                                String targetId, Object details, String clientIp, boolean success, String error) {
    }
}
