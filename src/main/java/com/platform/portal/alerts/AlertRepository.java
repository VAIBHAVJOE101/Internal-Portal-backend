package com.platform.portal.alerts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    Optional<Alert> findFirstByDedupeKeyAndStatusIn(String dedupeKey, Collection<Alert.Status> statuses);

    Optional<Alert> findFirstByDedupeKeyAndStatusAndResolvedAtAfterOrderByResolvedAtDesc(String dedupeKey, Alert.Status status, Instant after);

    List<Alert> findByDedupeKeyStartingWithAndStatusIn(String prefix, Collection<Alert.Status> statuses);

    List<Alert> findByStatusIn(Collection<Alert.Status> statuses);

    List<Alert> findByTypeAndStatusIn(AlertType type, Collection<Alert.Status> statuses);

    long countByStatusIn(Collection<Alert.Status> statuses);

    long countByStatusInAndSeverity(Collection<Alert.Status> statuses, Alert.Severity severity);

    List<Alert> findByFirstSeenAfter(Instant since);
}
