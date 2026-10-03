package com.platform.portal.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface InventoryPageRepository extends JpaRepository<InventoryPage, Long> {

    Optional<InventoryPage> findBySlug(String slug);

    List<InventoryPage> findAllByOrderBySortOrderAscNameAsc();

    boolean existsBySlug(String slug);
}

interface InventoryColumnRepository extends JpaRepository<InventoryColumn, Long> {

    List<InventoryColumn> findByPageIdOrderBySortOrderAscIdAsc(Long pageId);

    List<InventoryColumn> findByPageIdIn(Collection<Long> pageIds);

    List<InventoryColumn> findByExpiryTrackingTrue();

    @Modifying
    @Query("delete from InventoryColumn c where c.pageId = :pageId")
    void deleteByPageId(Long pageId);
}

interface InventoryRecordRepository extends JpaRepository<InventoryRecord, Long> {

    List<InventoryRecord> findByPageIdOrderByIdAsc(Long pageId);

    List<InventoryRecord> findByPageIdIn(Collection<Long> pageIds);

    long countByPageId(Long pageId);

    @Query("select r.pageId, count(r) from InventoryRecord r group by r.pageId")
    List<Object[]> countByPage();

    @Modifying
    @Query("delete from InventoryRecord r where r.pageId = :pageId")
    void deleteByPageId(Long pageId);
}
