package com.platform.portal.appkafka;

import java.util.List;
import java.util.Map;

import com.platform.portal.appkafka.RoutesModels.ChangeResult;
import com.platform.portal.appkafka.RoutesModels.RouteRow;
import com.platform.portal.appkafka.RoutesModels.RoutesConfig;

/** Source of API Gateway routes (Cosmos DB in real mode). */
public interface RoutesGateway {

    RoutesConfig config();

    List<RouteRow> rows();

    /** Applies field changes, keyed by row id then field path. Returns one result per row. */
    List<ChangeResult> apply(Map<String, Map<String, String>> changes);
}
