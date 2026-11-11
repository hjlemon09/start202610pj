package com.sapcommerce.analyzer;

import java.util.List;

/** Report-only filename correlation. Never changes resolution or reachability. */
final class LoadingEntryRelations {
    record Relation(String status, int count, String entries, String explanation) { }
    private LoadingEntryRelations() { }

    static Relation find(SpringLoadingAnalyzer.EntryMatch missing, List<SpringLoadingAnalyzer.EntryMatch> entries) {
        if (!missing.match().status().equals("NOT_FOUND_IN_SCOPE")) {
            return new Relation("NOT_APPLICABLE", 0, "", "");
        }
        String name = filename(missing.declaration().searchLocation());
        if (name.isEmpty()) {
            return new Relation("UNSUPPORTED_FILENAME_COMPARISON", 0, "",
                    "파일명을 단일 값으로 비교할 수 없습니다. 원래의 미발견 판정은 유지됩니다.");
        }
        List<SpringLoadingAnalyzer.EntryMatch> candidates = entries.stream()
                .filter(other -> !other.declaration().id().equals(missing.declaration().id()))
                .filter(other -> !other.match().status().equals("FOUND_IN_UNSELECTED_EXTENSION"))
                .filter(other -> !other.match().targetLocation().isEmpty() && other.match().targetStatus().startsWith("PARSED"))
                .filter(other -> name.equals(filename(other.match().targetLocation())))
                .distinct().toList();
        String details = candidates.stream().map(other -> {
            var d = other.declaration();
            return "entry_id=" + d.id() + "; owner_extension=" + d.extension() + "; modeled_context=" + d.context()
                    + "; context_kind=" + d.contextKind() + "; declaration_source=" + d.source()
                    + "; configuration=" + d.location() + "; target_xml=" + other.match().targetLocation()
                    + "; match_status=" + other.match().status() + "; target_parse_status=" + other.match().targetStatus()
                    + "; property_evidence=" + d.evidence() + "; runtime_loading_status=NOT_EVALUATED";
        }).collect(java.util.stream.Collectors.joining("\n"));
        return new Relation(candidates.isEmpty() ? "NO_PARSED_ENTRY_WITH_SAME_FILENAME" : "SAME_FILENAME_ONLY",
                candidates.size(), details, candidates.isEmpty()
                ? "다른 진입점에서 같은 파일명으로 발견·파싱된 대상이 없습니다. 원래의 미발견 판정은 유지됩니다. 파일 자체가 없다는 뜻은 아닙니다."
                : "원래 설정 경로에서는 미발견입니다. 다른 진입점에서 같은 파일명의 XML이 발견·파싱되었습니다. "
                    + "파일명만 비교한 참고 후보이며 동일 파일·대체 로딩·동일 컨텍스트의 증거가 아닙니다. "
                    + "CORE와 WEB은 별개이며 원래 미발견 판정과 실제 실행 미검증 상태는 유지됩니다.");
    }

    private static String filename(String location) {
        String normalized = location.replace('\\', '/');
        String name = normalized.substring(Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf(':')) + 1);
        return name.isBlank() || name.matches(".*[?*${}].*") ? "" : name;
    }
}
