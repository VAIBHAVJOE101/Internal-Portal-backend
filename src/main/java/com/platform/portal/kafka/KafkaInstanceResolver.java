package com.platform.portal.kafka;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import com.platform.portal.inventory.InventoryChangedEvent;
import com.platform.portal.inventory.InventoryDtos.RecordDto;
import com.platform.portal.inventory.InventoryService;
import com.platform.portal.inventory.SystemPages;
import com.platform.portal.kafka.KafkaModels.KafkaInstance;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Kafka module has no cluster table of its own: instances are the records of the
 * {@value SystemPages#KAFKA_INSTANCES} inventory page. Editing broker or Connect/sink IPs in
 * Inventory changes what the Kafka page connects to, and cached clients are evicted on change.
 */
@Component
public class KafkaInstanceResolver {

    private static final Set<String> CORE = Set.of("name", "environment", "brokers", "connectUrls", "securityProtocol",
            "saslMechanism", "credentialRef", "enabled");

    private final InventoryService inventory;
    private final KafkaGateway gateway;

    public KafkaInstanceResolver(InventoryService inventory, KafkaGateway gateway) {
        this.inventory = inventory;
        this.gateway = gateway;
    }

    public List<KafkaInstance> all() {
        return inventory.allRecords(SystemPages.KAFKA_INSTANCES).stream().map(KafkaInstanceResolver::toInstance).toList();
    }

    public List<KafkaInstance> enabled() {
        return all().stream().filter(KafkaInstance::enabled).toList();
    }

    public KafkaInstance get(Long id) {
        KafkaInstance instance = all().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Kafka instance " + id));
        if (instance.brokers().isEmpty()) {
            throw ApiException.badRequest("Kafka instance '" + instance.name() + "' has no broker addresses in Inventory");
        }
        return instance;
    }

    @EventListener
    void onInventoryChange(InventoryChangedEvent event) {
        if (!SystemPages.KAFKA_INSTANCES.equals(event.pageSlug())) {
            return;
        }
        if (event.recordId() != null) {
            gateway.evict(event.recordId());
        } else {
            all().forEach(i -> gateway.evict(i.id()));
        }
    }

    @EventListener
    void onSettingsChange(SettingsChangedEvent event) {
        if (event.type() == SettingType.KAFKA_CREDENTIAL) {
            all().stream().filter(i -> event.key().equals(i.credentialRef())).forEach(i -> gateway.evict(i.id()));
        }
    }

    static KafkaInstance toInstance(RecordDto record) {
        Map<String, Object> d = record.data();
        Map<String, Object> extra = new LinkedHashMap<>();
        d.forEach((k, v) -> {
            if (!CORE.contains(k)) extra.put(k, v);
        });
        String protocol = Strings.str(d, "securityProtocol");
        return new KafkaInstance(record.id(),
                Strings.str(d, "name") == null ? "instance-" + record.id() : Strings.str(d, "name"),
                Strings.str(d, "environment"),
                Strings.asList(d.get("brokers")),
                Strings.asList(d.get("connectUrls")).stream().map(KafkaInstanceResolver::normalizeUrl).toList(),
                protocol == null ? "PLAINTEXT" : protocol,
                Strings.str(d, "saslMechanism"),
                Strings.trimToNull(Strings.str(d, "credentialRef")),
                !Boolean.FALSE.equals(d.get("enabled")),
                extra);
    }

    private static String normalizeUrl(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
