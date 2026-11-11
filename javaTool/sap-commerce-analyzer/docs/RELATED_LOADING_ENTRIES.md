# 미발견 진입점과 별도 진입점의 관련 정보 표시

## 개선 이유

acceleratorstorefrontcommons의 project.properties에는 다음 설정이 있었다.

```properties
acceleratorstorefrontcommons.application-context=acceleratorstorefrontcommons-spring.xml
```

해당 파일명은 클래스패스 시작점 바로 아래에서 발견되지 않았다.
반면 Storefront에는 하위 경로까지 지정한 별도 설정이 있었다.

```properties
melcostorefront.additionalWebSpringConfigs.acceleratorstorefrontcommons=classpath:/acceleratorstorefrontcommons/web/spring/acceleratorstorefrontcommons-spring.xml
```

분석기는 후자의 WEB 진입점에서 XML을 발견하고 파싱했지만, 사용자가 이슈 CSV와
진입점 CSV를 직접 대조해야 관계를 이해할 수 있었다.

## 변경 내용

CSV 파일 수는 기존 18개를 유지한다. 발견/미발견 상태, 이슈 수, 도달 관계는 변경하지 않는다.
보고서 작성 시 다음 두 CSV에 관련 정보를 덧붙인다.

- spring-loading-issues.csv: 기존 source/status/detail을 유지하고 원래 진입점과 관련 진입점 정보를 추가
- spring-loading-entrypoints.csv: 기존 열을 유지하고 관련 상태/후보 수/후보 상세/설명 열 추가

이슈 CSV에서 확인할 열:

| 열 | 의미 |
| --- | --- |
| entry_id / owner_extension / modeled_context / context_kind | 원래 검사한 진입점과 컨텍스트 |
| configuration / search_location | 원래 설정값과 분석기가 사용한 검색 경로 |
| property_evidence | 해당 설정값을 읽은 properties 파일 등의 근거 |
| related_entry_status | 관련 정보 비교 결과 |
| related_entry_count | 같은 파일명의 발견·파싱된 다른 진입점 매칭 행 수 |
| related_entries | 후보마다 진입점 ID, 소유 Extension, 컨텍스트, 설정 키, 설정값, 실제 대상, 파싱 상태, 출처 |
| assessment | 원래 미발견과 별도 발견의 관계 및 해석 한계에 대한 한국어 설명 |
| runtime_loading_status | NOT_EVALUATED |

related_entries는 후보가 여러 개이면 한 셀 안에 줄바꿈으로 나열한다.
Excel에서는 해당 셀의 텍스트 줄 바꿈을 켜고 행 높이를 조절하면 된다.

## 조건 → 결과

1. 진입점 상태가 NOT_FOUND_IN_SCOPE이다.
   → 다른 진입점의 실제 대상 경로에서 같은 파일명을 비교한다.
2. 다른 진입점이 대상을 발견하고 파싱했다.
   → SAME_FILENAME_ONLY로 표시하고 그 진입점의 선언/실제 경로/출처를 함께 보여준다.
3. 발견·파싱한 다른 진입점이 없다.
   → NO_PARSED_ENTRY_WITH_SAME_FILENAME으로 표시한다. 파일 자체가 없다는 증명은 아니다.
4. 와일드카드/미해석 표현식 등으로 단일 파일명을 비교할 수 없다.
   → UNSUPPORTED_FILENAME_COMPARISON으로 표시한다.
5. 그 밖의 상태 또는 진입점에 직접 연결되지 않는 이슈이다.
   → NOT_APPLICABLE로 표시한다.

단순히 디스크에 파일이 있다는 이유로 관련 후보에 넣지 않는다.
비선택 Extension의 진단용 발견과 파싱 실패는 성공 후보로 넣지 않는다.
이슈는 source와 명시적인 ENTRY_ID를 함께 확인해 해당 진입점에 연결한다.
import 확장 중단 이슈를 파일명만으로 임의 연결하지 않는다.

## 판단의 한계

SAME_FILENAME_ONLY는 파일명만 같은 참고 후보다. 서로 다른 경로의 파일은 내용과 역할이 다를 수 있다.
후보가 여러 개이면 모두 표시하며 임의로 하나를 정답으로 선택하지 않는다.
WEB에서 발견됐어도 CORE 설정의 미발견이 해결된 것으로 바꾸지 않는다.
실제 서버 로딩, Bean 등록, 컨텍스트 간 대체 가능성은 확인하지 않는다.

이번 변경은 보고서 연결 기능에 한정한다. 앞서 사용자 제공 코드에서 확인한 SAP의
파일시스템 우선 검색, 실행 작업 디렉터리, 전체 클래스패스 등록/속성 덮어쓰기 규칙을
재현하는 변경은 포함하지 않는다. 기존 search_location은 분석 모델의 검색 경로다.
사용자 제공 getResource/build 코드에서는 단순 미발견 시 건너뛰는 흐름을 확인했지만,
이 보고서 기능은 모든 종류의 미발견을 실행 시 안전하다고 판정하지 않는다.

## 검증

LoadingEntryRelationsTest는 실제 가상 Extension/WEB 설정으로 다음을 확인한다.

- CORE 파일명 경로 미발견과 WEB 하위 경로 발견·파싱을 같은 보고서에 표시
- 설정 키와 properties 출처 표시
- 원래 미발견 상태 및 도달 관계 유지
- 등록되지 않은 같은 이름 파일/비선택 후보 제외
- 후보 없음/여러 후보의 보수적 표시
- 기존 CSV 열 유지와 관련 열 추가

기존 SpringLoadingAnalyzerTest 등 기존 분석 테스트도 함께 실행한다.

## 개발환경에서 확인할 것

변경 소스를 반영한 후 기존대로 ExtensionScanner를 실행한다.
CSV 18개 중 spring-loading-issues.csv에서
source=acceleratorstorefrontcommons.application-context 행을 찾는다.
같은 입력 조건이라면 원래 status는 유지되고 related_entry_status=SAME_FILENAME_ONLY,
related_entries에는 WEB:melcostorefront의 전체 경로 및 파싱 상태가 표시되어야 한다.
properties 출처는 property_evidence와 후보 상세에서 확인한다.
NOT_FOUND_IN_SCOPE와 ENTRYPOINT_NOT_FOUND_IN_SCOPE 두 진단이 함께 나오는 기존 동작도 유지한다.
