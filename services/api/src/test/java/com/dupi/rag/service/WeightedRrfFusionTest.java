package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightedRrfFusionTest {

    @Test
    void fusesWeightedRoutesByReciprocalRankAndAcceptsEmptyRoutes() {
        var fused = new WeightedRrfFusion().fuse(List.of(
                new WeightedRrfFusion.Route(1.0, List.of(hit("child-a"), hit("child-b"))),
                new WeightedRrfFusion.Route(0.8, List.of(hit("qa-a"))),
                new WeightedRrfFusion.Route(1.0, List.of())
        ), 60);

        assertThat(fused).extracting(MilvusVectorService.SearchResult::chunkId)
                .containsExactly("child-a", "child-b", "qa-a");
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61.0);
    }

    @Test
    void rejectsNonPositiveWeightsAndRrfK() {
        WeightedRrfFusion fusion = new WeightedRrfFusion();

        assertThatThrownBy(() -> fusion.fuse(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(
                new WeightedRrfFusion.Route(0, List.of(hit("a")))
        ), 60)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MilvusVectorService.SearchResult hit(String id) {
        return new MilvusVectorService.SearchResult(id, "doc", id, 1.0);
    }
}
