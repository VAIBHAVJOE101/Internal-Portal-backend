package com.platform.portal.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import com.platform.portal.common.PageResult;
import com.platform.portal.common.Strings;
import com.platform.portal.inventory.InventoryDtos.ColumnDto;
import com.platform.portal.inventory.InventoryDtos.ColumnRequest;
import com.platform.portal.inventory.InventoryDtos.ExpiringItem;
import com.platform.portal.inventory.InventoryDtos.ImportResult;
import com.platform.portal.inventory.InventoryDtos.PageDto;
import com.platform.portal.inventory.InventoryDtos.PageRequest;
import com.platform.portal.inventory.InventoryDtos.RecordDto;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dynamic inventory: pages with user-defined column schemas and JSON records validated against them.
 */
@Service
public class InventoryService {

    private static final int MAX_COLUMNS = 60;

    private final InventoryPageRepository pages;
    private final InventoryColumnRepository columns;
    private final InventoryRecordRepository records;
    private final Json json;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    InventoryService(InventoryPageRepository pages, InventoryColumnRepository columns, InventoryRecordRepository records,
                     Json json, AuditService audit, ApplicationEventPublisher events) {
        this.pages = pages;
        this.columns = columns;
        this.records = records;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    // ---------------------------------------------------------------- pages

    @Transactional(readOnly = true)
    public List<PageDto> listPages() {
        List<InventoryPage> all = pages.findAllByOrderBySortOrderAscNameAsc();
        Map<Long, List<InventoryColumn>> colsByPage = columns.findByPageIdIn(all.stream().map(InventoryPage::getId).toList())
                .stream().collect(Collectors.groupingBy(InventoryColumn::getPageId));
        Map<Long, Long> counts = records.countByPage().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        return all.stream().map(p -> toDto(p, sortColumns(colsByPage.getOrDefault(p.getId(), List.of())),
                counts.getOrDefault(p.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public PageDto getPage(String slug) {
        InventoryPage page = page(slug);
        return toDto(page, columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId()), records.countByPageId(page.getId()));
    }

    @Transactional
    public PageDto createPage(PageRequest request) {
        String slug = uniqueSlug(Strings.isBlank(request.slug()) ? request.name() : request.slug());
        return audit.track("INVENTORY_PAGE_CREATE", "inventory-page", slug, Map.of("name", request.name()), () -> {
            InventoryPage page = new InventoryPage();
            page.setSlug(slug);
            applyPage(page, request);
            page.setSortOrder(pages.findAll().stream().mapToInt(InventoryPage::getSortOrder).max().orElse(0) + 1);
            page.setCreatedAt(Instant.now());
            page.setUpdatedAt(Instant.now());
            pages.save(page);
            List<ColumnRequest> cols = request.columns() == null || request.columns().isEmpty()
                    ? List.of(new ColumnRequest("name", "Name", ColumnType.TEXT, true, true, null, null, false, null))
                    : request.columns();
            int order = 0;
            for (ColumnRequest c : cols) {
                columns.save(newColumn(page.getId(), c, order++, false, existingKeys(page.getId())));
            }
            events.publishEvent(new InventoryChangedEvent(slug, null, "PAGE_CREATED"));
            return getPage(slug);
        });
    }

    @Transactional
    public PageDto updatePage(String slug, PageRequest request) {
        InventoryPage page = page(slug);
        audit.track("INVENTORY_PAGE_UPDATE", "inventory-page", slug,
                Map.of("before", Map.of("name", page.getName()), "after", Map.of("name", request.name())), () -> {
                    applyPage(page, request);
                    page.setUpdatedAt(Instant.now());
                });
        return getPage(slug);
    }

    @Transactional
    public void deletePage(String slug) {
        InventoryPage page = page(slug);
        if (page.isSystem()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "'" + page.getName() + "' is a system page used by other modules and cannot be deleted");
        }
        long count = records.countByPageId(page.getId());
        audit.track("INVENTORY_PAGE_DELETE", "inventory-page", slug, Map.of("name", page.getName(), "records", count), () -> {
            records.deleteByPageId(page.getId());
            columns.deleteByPageId(page.getId());
            pages.delete(page);
        });
        events.publishEvent(new InventoryChangedEvent(slug, null, "PAGE_DELETED"));
    }

    @Transactional
    public List<PageDto> reorderPages(List<String> slugs) {
        Map<String, InventoryPage> bySlug = pages.findAll().stream().collect(Collectors.toMap(InventoryPage::getSlug, Function.identity()));
        audit.track("INVENTORY_PAGE_REORDER", "inventory-page", null, Map.of("order", slugs), () -> {
            for (int i = 0; i < slugs.size(); i++) {
                InventoryPage p = bySlug.get(slugs.get(i));
                if (p != null) p.setSortOrder(i);
            }
        });
        return listPages();
    }

    // ---------------------------------------------------------------- columns

    @Transactional
    public PageDto addColumn(String slug, ColumnRequest request) {
        InventoryPage page = page(slug);
        List<InventoryColumn> existing = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId());
        if (existing.size() >= MAX_COLUMNS) {
            throw ApiException.badRequest("A page can have at most " + MAX_COLUMNS + " columns");
        }
        int order = existing.stream().mapToInt(InventoryColumn::getSortOrder).max().orElse(-1) + 1;
        InventoryColumn column = newColumn(page.getId(), request, order, false, existingKeys(page.getId()));
        audit.track("INVENTORY_COLUMN_ADD", "inventory-column", slug + "." + column.getKey(),
                Map.of("label", column.getLabel(), "type", column.getType().name()), () -> columns.save(column));
        touch(page);
        return getPage(slug);
    }

    @Transactional
    public PageDto updateColumn(String slug, String key, ColumnRequest request) {
        InventoryPage page = page(slug);
        InventoryColumn column = column(page, key);
        if (column.isLocked() && request.type() != column.getType()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Type of locked column '" + column.getLabel() + "' cannot change");
        }
        Map<String, Object> before = Map.of("label", column.getLabel(), "type", column.getType().name(), "visible", column.isVisible());
        audit.track("INVENTORY_COLUMN_UPDATE", "inventory-column", slug + "." + key,
                Map.of("before", before, "after", Map.of("label", request.label(), "type", request.type().name())), () -> {
                    column.setLabel(request.label().trim());
                    column.setType(request.type());
                    if (!column.isLocked() && request.required() != null) column.setRequired(request.required());
                    if (request.visible() != null) column.setVisible(request.visible());
                    column.setWidth(request.width());
                    column.setOptions(request.options() == null ? null : json.write(request.options()));
                    column.setExpiryTracking(Boolean.TRUE.equals(request.expiryTracking())
                            && (request.type() == ColumnType.DATE || request.type() == ColumnType.DATETIME));
                    column.setDescription(request.description());
                });
        touch(page);
        return getPage(slug);
    }

    @Transactional
    public PageDto deleteColumn(String slug, String key) {
        InventoryPage page = page(slug);
        InventoryColumn column = column(page, key);
        if (column.isLocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "'" + column.getLabel() + "' is a core column of a system page and cannot be deleted");
        }
        if (columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId()).size() <= 1) {
            throw ApiException.badRequest("A page needs at least one column");
        }
        audit.track("INVENTORY_COLUMN_DELETE", "inventory-column", slug + "." + key, Map.of("label", column.getLabel()), () -> {
            columns.delete(column);
            for (InventoryRecord r : records.findByPageIdOrderByIdAsc(page.getId())) {
                Map<String, Object> data = json.readMap(r.getData());
                if (data.remove(key) != null) {
                    r.setData(json.write(data));
                }
            }
        });
        touch(page);
        return getPage(slug);
    }

    @Transactional
    public PageDto reorderColumns(String slug, List<String> keys) {
        InventoryPage page = page(slug);
        Map<String, InventoryColumn> byKey = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId()).stream()
                .collect(Collectors.toMap(InventoryColumn::getKey, Function.identity()));
        audit.track("INVENTORY_COLUMN_REORDER", "inventory-page", slug, Map.of("order", keys), () -> {
            int i = 0;
            for (String key : keys) {
                InventoryColumn c = byKey.remove(key);
                if (c != null) c.setSortOrder(i++);
            }
            for (InventoryColumn rest : byKey.values()) rest.setSortOrder(i++);
        });
        touch(page);
        return getPage(slug);
    }

    // ---------------------------------------------------------------- records

    @Transactional(readOnly = true)
    public PageResult<RecordDto> listRecords(String slug, String q, String sort, String dir, Map<String, String> filters,
                                             int page, int size) {
        InventoryPage p = page(slug);
        Map<String, InventoryColumn> cols = columns.findByPageIdOrderBySortOrderAscIdAsc(p.getId()).stream()
                .collect(Collectors.toMap(InventoryColumn::getKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<RecordDto> all = records.findByPageIdOrderByIdAsc(p.getId()).stream().map(this::toDto).toList();
        String needle = Strings.isBlank(q) ? null : q.toLowerCase();
        List<RecordDto> filtered = all.stream()
                .filter(r -> needle == null || r.data().values().stream().anyMatch(v -> v != null && v.toString().toLowerCase().contains(needle)))
                .filter(r -> matchesFilters(r, filters, cols))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!Strings.isBlank(sort)) {
            Comparator<RecordDto> cmp = Comparator.comparing(r -> r.data().get(sort), InventoryService::compareValues);
            if ("desc".equalsIgnoreCase(dir)) cmp = cmp.reversed();
            filtered.sort(cmp);
        }
        return PageResult.slice(filtered, page, Math.min(Math.max(size, 1), 1000));
    }

    @Transactional(readOnly = true)
    public List<RecordDto> allRecords(String slug) {
        return records.findByPageIdOrderByIdAsc(page(slug).getId()).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public RecordDto getRecord(String slug, Long id) {
        return toDto(record(page(slug), id));
    }

    @Transactional
    public RecordDto createRecord(String slug, Map<String, Object> input) {
        InventoryPage page = page(slug);
        Map<String, Object> data = validate(page, input);
        InventoryRecord record = new InventoryRecord();
        record.setPageId(page.getId());
        record.setData(json.write(data));
        record.setCreatedBy(CurrentUser.username());
        record.setCreatedAt(Instant.now());
        record.setUpdatedBy(CurrentUser.username());
        record.setUpdatedAt(Instant.now());
        audit.track("INVENTORY_RECORD_CREATE", "inventory-record", slug, Map.of("after", data), () -> records.save(record));
        events.publishEvent(new InventoryChangedEvent(slug, record.getId(), "RECORD_CREATED"));
        return toDto(record);
    }

    @Transactional
    public RecordDto updateRecord(String slug, Long id, Map<String, Object> input) {
        InventoryPage page = page(slug);
        InventoryRecord record = record(page, id);
        Map<String, Object> before = json.readMap(record.getData());
        Map<String, Object> data = validate(page, input);
        audit.track("INVENTORY_RECORD_UPDATE", "inventory-record", slug + "/" + id, diff(before, data), () -> {
            record.setData(json.write(data));
            record.setUpdatedBy(CurrentUser.username());
            record.setUpdatedAt(Instant.now());
        });
        events.publishEvent(new InventoryChangedEvent(slug, id, "RECORD_UPDATED"));
        return toDto(record);
    }

    @Transactional
    public void deleteRecords(String slug, Collection<Long> ids) {
        InventoryPage page = page(slug);
        List<InventoryRecord> targets = records.findAllById(ids).stream().filter(r -> r.getPageId().equals(page.getId())).toList();
        if (targets.isEmpty()) {
            throw ApiException.notFound("Records");
        }
        audit.track("INVENTORY_RECORD_DELETE", "inventory-record", slug,
                Map.of("ids", targets.stream().map(InventoryRecord::getId).toList(),
                        "before", targets.stream().map(r -> json.readMap(r.getData())).toList()),
                () -> records.deleteAll(targets));
        targets.forEach(r -> events.publishEvent(new InventoryChangedEvent(slug, r.getId(), "RECORD_DELETED")));
    }

    @Transactional(readOnly = true)
    public String exportCsv(String slug) {
        InventoryPage page = page(slug);
        List<InventoryColumn> cols = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId());
        StringBuilder sb = new StringBuilder();
        List<Object> header = new ArrayList<>();
        header.add("id");
        cols.forEach(c -> header.add(c.getKey()));
        sb.append(Csv.row(header));
        for (InventoryRecord r : records.findByPageIdOrderByIdAsc(page.getId())) {
            Map<String, Object> data = json.readMap(r.getData());
            List<Object> row = new ArrayList<>();
            row.add(r.getId());
            cols.forEach(c -> row.add(data.get(c.getKey())));
            sb.append(Csv.row(row));
        }
        return sb.toString();
    }

    /** Imports rows; the header may use column keys or labels. Rows with an existing id are updated. */
    @Transactional
    public ImportResult importCsv(String slug, String csv) {
        InventoryPage page = page(slug);
        List<InventoryColumn> cols = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId());
        List<List<String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("CSV is empty");
        }
        List<String> header = rows.get(0);
        Map<Integer, String> mapping = new HashMap<>();
        int idIndex = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim();
            if (h.equalsIgnoreCase("id")) {
                idIndex = i;
                continue;
            }
            for (InventoryColumn c : cols) {
                if (c.getKey().equalsIgnoreCase(h) || c.getLabel().equalsIgnoreCase(h)) {
                    mapping.put(i, c.getKey());
                }
            }
        }
        if (mapping.isEmpty()) {
            throw ApiException.badRequest("No CSV header matches a column key or label of this page");
        }
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            Map<String, Object> input = new LinkedHashMap<>();
            mapping.forEach((i, key) -> input.put(key, i < row.size() ? row.get(i) : null));
            try {
                Map<String, Object> data = validate(page, input);
                Long id = idIndex >= 0 && idIndex < row.size() && !row.get(idIndex).isBlank() ? Long.parseLong(row.get(idIndex).trim()) : null;
                InventoryRecord record = id == null ? null : records.findById(id).filter(x -> x.getPageId().equals(page.getId())).orElse(null);
                if (record == null) {
                    record = new InventoryRecord();
                    record.setPageId(page.getId());
                    record.setCreatedBy(CurrentUser.username());
                    record.setCreatedAt(Instant.now());
                    created++;
                } else {
                    updated++;
                }
                record.setData(json.write(data));
                record.setUpdatedBy(CurrentUser.username());
                record.setUpdatedAt(Instant.now());
                records.save(record);
            } catch (IllegalArgumentException | ApiException e) {
                errors.add("Row " + (r + 1) + ": " + e.getMessage());
            }
        }
        audit.record("INVENTORY_IMPORT", "inventory-page", slug,
                Map.of("created", created, "updated", updated, "errors", errors.size()), errors.isEmpty(), null);
        events.publishEvent(new InventoryChangedEvent(slug, null, "IMPORTED"));
        return new ImportResult(created + updated, errors);
    }

    // ---------------------------------------------------------------- expiry

    /** All expiry-tracked date values due within {@code days} (including already expired), soonest first. */
    @Transactional(readOnly = true)
    public List<ExpiringItem> expiring(int days) {
        List<InventoryColumn> tracked = columns.findByExpiryTrackingTrue();
        if (tracked.isEmpty()) {
            return List.of();
        }
        Map<Long, List<InventoryColumn>> byPage = tracked.stream().collect(Collectors.groupingBy(InventoryColumn::getPageId));
        Map<Long, InventoryPage> pageById = pages.findAllById(byPage.keySet()).stream()
                .collect(Collectors.toMap(InventoryPage::getId, Function.identity()));
        Map<Long, List<InventoryColumn>> allCols = columns.findByPageIdIn(byPage.keySet()).stream()
                .collect(Collectors.groupingBy(InventoryColumn::getPageId));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ExpiringItem> items = new ArrayList<>();
        for (InventoryRecord r : records.findByPageIdIn(byPage.keySet())) {
            Map<String, Object> data = json.readMap(r.getData());
            InventoryPage page = pageById.get(r.getPageId());
            String title = titleOf(data, sortColumns(allCols.getOrDefault(r.getPageId(), List.of())));
            for (InventoryColumn c : byPage.get(r.getPageId())) {
                Object v = data.get(c.getKey());
                if (v == null) continue;
                try {
                    LocalDate due = LocalDate.parse(v.toString().substring(0, 10));
                    long left = ChronoUnit.DAYS.between(today, due);
                    if (left <= days) {
                        items.add(new ExpiringItem(page.getSlug(), page.getName(), r.getId(), title, c.getKey(), c.getLabel(),
                                due.toString(), left));
                    }
                } catch (RuntimeException ignored) {
                    // malformed date - validation will flag it on next edit
                }
            }
        }
        items.sort(Comparator.comparingLong(ExpiringItem::daysLeft));
        return items;
    }

    // ---------------------------------------------------------------- system pages

    /** Creates a system page if missing and makes sure its locked core columns exist. */
    @Transactional
    public void ensureSystemPage(SystemPages.Definition def) {
        InventoryPage page = pages.findBySlug(def.slug()).orElseGet(() -> {
            InventoryPage p = new InventoryPage();
            p.setSlug(def.slug());
            p.setName(def.name());
            p.setDescription(def.description());
            p.setIcon(def.icon());
            p.setGroupName(def.group());
            p.setSortOrder(0);
            p.setCreatedAt(Instant.now());
            p.setUpdatedAt(Instant.now());
            return pages.save(p);
        });
        page.setSystem(true);
        List<InventoryColumn> existing = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId());
        Map<String, InventoryColumn> byKey = existing.stream().collect(Collectors.toMap(InventoryColumn::getKey, Function.identity()));
        int order = 0;
        for (ColumnRequest c : def.columns()) {
            InventoryColumn col = byKey.get(c.key());
            if (col == null) {
                col = newColumn(page.getId(), c, order, true, byKey.keySet());
                columns.save(col);
            } else {
                col.setLocked(true);
                col.setType(c.type());
                col.setRequired(Boolean.TRUE.equals(c.required()));
            }
            order++;
        }
    }

    public boolean hasPages() {
        return pages.count() > 0;
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> validate(InventoryPage page, Map<String, Object> input) {
        List<InventoryColumn> cols = columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (InventoryColumn c : cols) {
            try {
                Object value = ValueValidator.normalize(c, input.get(c.getKey()), json.readMap(c.getOptions()));
                if (value != null && c.getType() == ColumnType.REFERENCE) {
                    checkReference(c, (Long) value);
                }
                if (value != null) {
                    data.put(c.getKey(), value);
                }
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
        return data;
    }

    private void checkReference(InventoryColumn column, Long id) {
        String refPage = Strings.str(json.readMap(column.getOptions()), "refPage");
        if (refPage == null) return;
        InventoryPage target = pages.findBySlug(refPage).orElse(null);
        if (target == null || records.findById(id).filter(r -> r.getPageId().equals(target.getId())).isEmpty()) {
            throw new IllegalArgumentException(column.getLabel() + ": referenced record " + id + " does not exist");
        }
    }

    private boolean matchesFilters(RecordDto r, Map<String, String> filters, Map<String, InventoryColumn> cols) {
        for (Map.Entry<String, String> f : filters.entrySet()) {
            InventoryColumn c = cols.get(f.getKey());
            if (c == null || Strings.isBlank(f.getValue())) continue;
            Object v = r.data().get(f.getKey());
            String expected = f.getValue().toLowerCase();
            boolean exact = c.getType() == ColumnType.SELECT || c.getType() == ColumnType.BOOLEAN;
            if (v == null) return false;
            if (v instanceof Collection<?> list) {
                if (list.stream().noneMatch(x -> x.toString().toLowerCase().contains(expected))) return false;
            } else if (exact ? !v.toString().equalsIgnoreCase(expected) : !v.toString().toLowerCase().contains(expected)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        if (a instanceof Number na && b instanceof Number nb) return Double.compare(na.doubleValue(), nb.doubleValue());
        if (a instanceof Boolean ba && b instanceof Boolean bb) return ba.compareTo(bb);
        return a.toString().compareToIgnoreCase(b.toString());
    }

    private InventoryColumn newColumn(Long pageId, ColumnRequest req, int order, boolean locked, Collection<String> taken) {
        String key = Strings.keyify(Strings.isBlank(req.key()) ? req.label() : req.key());
        if (key.equals("id")) key = "recordId";
        String base = key;
        int n = 2;
        while (taken.contains(key)) {
            key = base + n++;
        }
        InventoryColumn c = new InventoryColumn();
        c.setPageId(pageId);
        c.setKey(key);
        c.setLabel(req.label().trim());
        c.setType(req.type());
        c.setRequired(Boolean.TRUE.equals(req.required()));
        c.setVisible(req.visible() == null || req.visible());
        c.setWidth(req.width());
        c.setSortOrder(order);
        c.setLocked(locked);
        c.setOptions(req.options() == null ? null : json.write(req.options()));
        c.setExpiryTracking(Boolean.TRUE.equals(req.expiryTracking()) && (req.type() == ColumnType.DATE || req.type() == ColumnType.DATETIME));
        c.setDescription(req.description());
        return c;
    }

    private List<String> existingKeys(Long pageId) {
        return columns.findByPageIdOrderBySortOrderAscIdAsc(pageId).stream().map(InventoryColumn::getKey).toList();
    }

    private void applyPage(InventoryPage page, PageRequest request) {
        page.setName(request.name().trim());
        page.setDescription(request.description());
        page.setIcon(Strings.isBlank(request.icon()) ? "database" : request.icon());
        page.setGroupName(Strings.isBlank(request.group()) ? "Inventory" : request.group().trim());
    }

    private String uniqueSlug(String input) {
        String base = Strings.slugify(input);
        String slug = base;
        int n = 2;
        while (pages.existsBySlug(slug)) {
            slug = base + "-" + n++;
        }
        return slug;
    }

    private void touch(InventoryPage page) {
        page.setUpdatedAt(Instant.now());
        events.publishEvent(new InventoryChangedEvent(page.getSlug(), null, "SCHEMA_CHANGED"));
    }

    private InventoryPage page(String slug) {
        return pages.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Inventory page '" + slug + "'"));
    }

    private InventoryColumn column(InventoryPage page, String key) {
        return columns.findByPageIdOrderBySortOrderAscIdAsc(page.getId()).stream().filter(c -> c.getKey().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Column '" + key + "'"));
    }

    private InventoryRecord record(InventoryPage page, Long id) {
        return records.findById(id).filter(r -> r.getPageId().equals(page.getId()))
                .orElseThrow(() -> ApiException.notFound("Record " + id));
    }

    private static List<InventoryColumn> sortColumns(List<InventoryColumn> cols) {
        return cols.stream().sorted(Comparator.comparingInt(InventoryColumn::getSortOrder).thenComparing(InventoryColumn::getId)).toList();
    }

    private static String titleOf(Map<String, Object> data, List<InventoryColumn> cols) {
        return cols.stream().map(c -> data.get(c.getKey())).filter(Objects::nonNull).map(Object::toString).findFirst().orElse("(untitled)");
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            if (!Objects.equals(before.get(k), after.get(k))) {
                b.put(k, before.get(k));
                a.put(k, after.get(k));
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("before", b);
        d.put("after", a);
        return d;
    }

    private PageDto toDto(InventoryPage p, List<InventoryColumn> cols, long count) {
        return new PageDto(p.getId(), p.getSlug(), p.getName(), p.getDescription(), p.getIcon(), p.getGroupName(), p.getSortOrder(),
                p.isSystem(), cols.stream().map(this::toDto).toList(), count, p.getUpdatedAt());
    }

    private ColumnDto toDto(InventoryColumn c) {
        return new ColumnDto(c.getId(), c.getKey(), c.getLabel(), c.getType(), c.isRequired(), c.isLocked(), c.isVisible(),
                c.getWidth(), c.getSortOrder(), c.getOptions() == null ? null : json.readMap(c.getOptions()), c.isExpiryTracking(),
                c.getDescription());
    }

    private RecordDto toDto(InventoryRecord r) {
        return new RecordDto(r.getId(), json.readMap(r.getData()), r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedBy(), r.getUpdatedAt());
    }
}
