package ru.petrovov.iib_camel.converter.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.GraphEdge;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Reachability;

final class StructuralReachability {
    private StructuralReachability() {
    }

    static Map<String, Reachability> classify(Set<String> roots, List<GraphEdge> edges) {
        return classify(roots, edges, Set.of());
    }

    static Map<String, Reachability> classify(Set<String> roots, List<GraphEdge> edges, Set<String> barriers) {
        Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        edges.forEach(edge -> outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(edge));
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        roots.forEach(root -> pending.add(new Visit(root, false)));
        Set<Visit> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Visit current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (barriers.contains(current.nodeId())) {
                continue;
            }
            for (GraphEdge edge : outgoing.getOrDefault(current.nodeId(), List.of())) {
                boolean error = current.error() || edge.sourceTerminal().equalsIgnoreCase("catch")
                        || edge.sourceTerminal().equalsIgnoreCase("failure");
                pending.add(new Visit(edge.targetId(), error));
            }
        }
        Map<String, Reachability> result = new HashMap<>();
        for (Visit visit : visited) {
            boolean normal = visited.contains(new Visit(visit.nodeId(), false));
            boolean error = visited.contains(new Visit(visit.nodeId(), true));
            result.put(visit.nodeId(), normal && error ? Reachability.STRUCTURAL_MIXED
                    : error ? Reachability.STRUCTURAL_ERROR : Reachability.STRUCTURAL_NORMAL);
        }
        return result;
    }

    private record Visit(String nodeId, boolean error) {
    }
}
