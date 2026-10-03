package com.platform.portal.prefs;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-user UI preferences such as saved inventory table views (visible columns, widths, sort). */
@RestController
@RequestMapping("/api/prefs")
public class UserPrefsController {

    private final UserViewPrefRepository repository;
    private final Json json;

    public UserPrefsController(UserViewPrefRepository repository, Json json) {
        this.repository = repository;
        this.json = json;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Object> get(@PathVariable String key) {
        return repository.findByUsernameAndViewKey(CurrentUser.username(), key)
                .map(p -> ResponseEntity.ok(json.read(p.getValue())))
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/{key}")
    @Transactional
    public Object put(@PathVariable String key, @RequestBody Map<String, Object> value) {
        if (key.length() > 200) {
            throw new IllegalArgumentException("Preference key too long");
        }
        UserViewPref pref = repository.findByUsernameAndViewKey(CurrentUser.username(), key).orElseGet(() -> {
            UserViewPref p = new UserViewPref();
            p.setUsername(CurrentUser.username());
            p.setViewKey(key);
            return p;
        });
        pref.setValue(json.write(value));
        pref.setUpdatedAt(Instant.now());
        repository.save(pref);
        return value;
    }

    @Entity
    @Table(name = "user_view_pref")
    public static class UserViewPref {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String username;
        private String viewKey;
        @Column(name = "pref_value")
        private String value;
        private Instant updatedAt;

        public Long getId() { return id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getViewKey() { return viewKey; }
        public void setViewKey(String viewKey) { this.viewKey = viewKey; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    public interface UserViewPrefRepository extends JpaRepository<UserViewPref, Long> {
        Optional<UserViewPref> findByUsernameAndViewKey(String username, String viewKey);
    }
}
