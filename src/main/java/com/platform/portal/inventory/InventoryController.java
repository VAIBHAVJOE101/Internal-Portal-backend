package com.platform.portal.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.platform.portal.common.PageResult;
import com.platform.portal.inventory.InventoryDtos.BulkDeleteRequest;
import com.platform.portal.inventory.InventoryDtos.ColumnRequest;
import com.platform.portal.inventory.InventoryDtos.ExpiringItem;
import com.platform.portal.inventory.InventoryDtos.ImportResult;
import com.platform.portal.inventory.InventoryDtos.PageDto;
import com.platform.portal.inventory.InventoryDtos.PageRequest;
import com.platform.portal.inventory.InventoryDtos.RecordDto;
import com.platform.portal.inventory.InventoryDtos.RecordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/pages")
    public List<PageDto> pages() {
        return service.listPages();
    }

    @GetMapping("/pages/{slug}")
    public PageDto page(@PathVariable String slug) {
        return service.getPage(slug);
    }

    @PostMapping("/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageDto createPage(@Valid @RequestBody PageRequest request) {
        return service.createPage(request);
    }

    @PutMapping("/pages/{slug}")
    public PageDto updatePage(@PathVariable String slug, @Valid @RequestBody PageRequest request) {
        return service.updatePage(slug, request);
    }

    @DeleteMapping("/pages/{slug}")
    public ResponseEntity<Void> deletePage(@PathVariable String slug) {
        service.deletePage(slug);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/pages-order")
    public List<PageDto> reorderPages(@RequestBody List<String> slugs) {
        return service.reorderPages(slugs);
    }

    @PostMapping("/pages/{slug}/columns")
    public PageDto addColumn(@PathVariable String slug, @Valid @RequestBody ColumnRequest request) {
        return service.addColumn(slug, request);
    }

    @PutMapping("/pages/{slug}/columns/{key}")
    public PageDto updateColumn(@PathVariable String slug, @PathVariable String key, @Valid @RequestBody ColumnRequest request) {
        return service.updateColumn(slug, key, request);
    }

    @DeleteMapping("/pages/{slug}/columns/{key}")
    public PageDto deleteColumn(@PathVariable String slug, @PathVariable String key) {
        return service.deleteColumn(slug, key);
    }

    @PutMapping("/pages/{slug}/columns-order")
    public PageDto reorderColumns(@PathVariable String slug, @RequestBody List<String> keys) {
        return service.reorderColumns(slug, keys);
    }

    /** Filters are passed as {@code f.<columnKey>=value}. */
    @GetMapping("/pages/{slug}/records")
    public PageResult<RecordDto> records(@PathVariable String slug,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false, defaultValue = "asc") String dir,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "100") int size,
                                         @RequestParam Map<String, String> params) {
        Map<String, String> filters = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("f.")) filters.put(k.substring(2), v);
        });
        return service.listRecords(slug, q, sort, dir, filters, page, size);
    }

    @GetMapping("/pages/{slug}/records/{id}")
    public RecordDto record(@PathVariable String slug, @PathVariable Long id) {
        return service.getRecord(slug, id);
    }

    @PostMapping("/pages/{slug}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordDto create(@PathVariable String slug, @Valid @RequestBody RecordRequest request) {
        return service.createRecord(slug, request.data());
    }

    @PutMapping("/pages/{slug}/records/{id}")
    public RecordDto update(@PathVariable String slug, @PathVariable Long id, @Valid @RequestBody RecordRequest request) {
        return service.updateRecord(slug, id, request.data());
    }

    @DeleteMapping("/pages/{slug}/records/{id}")
    public ResponseEntity<Void> delete(@PathVariable String slug, @PathVariable Long id) {
        service.deleteRecords(slug, List.of(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pages/{slug}/records/bulk-delete")
    public ResponseEntity<Void> bulkDelete(@PathVariable String slug, @Valid @RequestBody BulkDeleteRequest request) {
        service.deleteRecords(slug, request.ids());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/pages/{slug}/export", produces = "text/csv")
    public ResponseEntity<String> export(@PathVariable String slug) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slug + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.exportCsv(slug));
    }

    @PostMapping(path = "/pages/{slug}/import", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public ImportResult importCsv(@PathVariable String slug, @RequestBody String csv) {
        return service.importCsv(slug, csv);
    }

    @GetMapping("/expiring")
    public List<ExpiringItem> expiring(@RequestParam(defaultValue = "30") int days) {
        return service.expiring(days);
    }
}
