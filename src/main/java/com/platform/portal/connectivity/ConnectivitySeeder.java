package com.platform.portal.connectivity;

import com.platform.portal.config.PortalProperties;
import com.platform.portal.connectivity.ConnectivityService.TargetRequest;
import com.platform.portal.connectivity.ConnectivityTarget.ScheduleType;
import com.platform.portal.connectivity.ConnectivityTarget.TestType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Demo connectivity targets for mock mode (the probes themselves still run for real). */
@Component
@Order(2)
public class ConnectivitySeeder implements ApplicationRunner {

    private final ConnectivityService service;
    private final ConnectivityResult.TargetRepository targets;
    private final PortalProperties properties;

    public ConnectivitySeeder(ConnectivityService service, ConnectivityResult.TargetRepository targets, PortalProperties properties) {
        this.service = service;
        this.targets = targets;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isMock() || targets.count() > 0) {
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "system", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            service.create(new TargetRequest("GitHub API", TestType.HTTP, null, null, "https://api.github.com/zen", "GET", 200,
                    5000, ScheduleType.INTERVAL, 300, null, true, 3, "github,egress"));
            service.create(new TargetRequest("Azure DevOps", TestType.HTTP, null, null, "https://dev.azure.com", "HEAD", null,
                    5000, ScheduleType.CRON, null, "*/10 * * * *", true, 3, "azure,egress"));
            service.create(new TargetRequest("Cosmos DB endpoint TLS", TestType.TLS, "learn.microsoft.com", 443, null, null, null,
                    5000, ScheduleType.CRON, null, "0 */6 * * *", true, 2, "azure,tls"));
            service.create(new TargetRequest("Public DNS", TestType.DNS, "github.com", null, null, null, null,
                    3000, ScheduleType.INTERVAL, 600, null, true, 3, "dns"));
            service.create(new TargetRequest("Kafka PROD broker 1", TestType.TCP, "10.20.1.11", 9093, null, null, null,
                    2000, ScheduleType.INTERVAL, 120, null, true, 2, "kafka,prod"));
            service.create(new TargetRequest("Partner SFTP", TestType.TCP, "test.rebex.net", 22, null, null, null,
                    4000, ScheduleType.NONE, null, null, true, 3, "partner"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
