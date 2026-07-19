package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeightedRrfFusion {

    public List<MilvusVectorService.SearchResult> fuse(List<Route> routes, int rrfK) {
        if (rrfK < 1) {
            throw new IllegalArgumentException("RRF K must be at least 1");
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, MilvusVectorService.SearchResult> hits = new LinkedHashMap<>();
        for (Route route : routes) {
            if (route.weight() <= 0) {
                throw new IllegalArgumentException("Route weight must be positive");
            }
            for (int rank = 0; rank < route.hits().size(); rank++) {
                MilvusVectorService.SearchResult hit = route.hits().get(rank);
                hits.putIfAbsent(hit.chunkId(), hit);
                scores.merge(hit.chunkId(), route.weight() / (rrfK + rank + 1.0), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey));
        return ordered.stream().map(entry -> {
            MilvusVectorService.SearchResult hit = hits.get(entry.getKey());
            return new MilvusVectorService.SearchResult(
                    hit.chunkId(),
                    hit.docId(),
                    hit.content(),
                    entry.getValue()
            );
        }).toList();
    }

    public record Route(double weight, List<MilvusVectorService.SearchResult> hits) {
        public Route {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }
}
