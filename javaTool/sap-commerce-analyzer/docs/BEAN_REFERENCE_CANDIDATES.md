# Bean 참조의 정적 선언 후보 연결

## 목적

이전에는 converter의 property ref="populators"를 읽어 이름만 기록했다.
이번에는 같은 모델링 컨텍스트에 populators라는 이름의 선언이 있는지 찾아
원본 XML, 클래스 원문, alias 경로를 함께 보여준다.
서버 실행, Bean 생성, 리플렉션, 메서드 호출 추적은 하지 않는다.

## 조건 → 결과

1. ref 이름이 리터럴이다 → 같은 modeled_context의 선언 인덱스에서 이름을 찾는다.
2. id 또는 name 속성으로 선언을 찾았다 → 정적 후보에 넣는다.
3. alias 이름이다 → 같은 문맥의 alias 선언을 따라가며 대상 선언을 찾는다.
4. 선언 1개다 → SINGLE_SAME_CONTEXT_CANDIDATE. 최종 바인딩 확정은 아니다.
5. 선언 여러 개다 → MULTIPLE_SAME_CONTEXT_CANDIDATES. profile/override 승자를 추정하지 않는다.
6. 후보가 없다 → NO_SAME_CONTEXT_CANDIDATE. 다른 컨텍스트·스캔·동적 등록 등으로 해결될 수 있어 실행 오류로 단정하지 않는다.

## 이름과 문맥 처리

- 명시 id와 name 속성(쉼표/세미콜론/공백으로 구분)을 인덱싱한다.
- 같은 선언이 여러 이름으로 매칭되어도 후보 수는 declaration_key 기준으로 한 번 센다.
- 명시 id/name이 없는 선언에는 Spring 생성 이름을 추측해 부여하지 않는다.
- `${...}`/`#{...}`는 원문을 유지하고 DYNAMIC_NAME_NOT_EVALUATED로 분리한다.
- `&beanName`은 선언 후보 조회에서 접두사를 분리하되 FACTORY_DEREFERENCE_NOT_VALIDATED를 표시한다.
  실제 FactoryBean인지/반환 객체가 무엇인지 검증하지 않는다.
- 기존 잠재 도달성 자료에서 XML → modeled_context의 구조화된 목록을 만든다.
  CSV용 문자열을 다시 해석해 문맥을 만들지 않는다.
- CORE/GLOBAL/WEB:ext/SERVLET:ext:name을 서로 다른 문맥으로 취급한다.
- 하나의 XML이 여러 문맥에서 도달되면 참조도 문맥별 행으로 출력한다.
- 진입점이 확인되지 않은 XML은 같은 XML 내부만 대조한다. SAME_XML_CANDIDATES_ONLY 또는 NO_CANDIDATE_IN_SAME_XML로 표시한다.
- 부모 컨텍스트 가시성은 재현하지 않는다. `<ref parent="...">`는 PARENT_CONTEXT_NOT_MODELED로 남긴다.
  CORE를 WEB의 부모라고 임의로 연결하거나 관계 없는 WEB의 이름을 후보로 섞지 않는다.
- 오래된 ref/local 및 idref/local은 같은 XML 내 선언/alias로 제한한다.
- 내부 Bean의 이름은 전역 이름 인덱스에서 제외한다.
  INNER_BEAN/INNER_UTIL_DECLARATION만 선언 키·동일 XML·소유자 일치로 직접 포함 관계를 연결한다.
- PARENT_DEFINITION은 부모 Bean 정의 이름의 후보 조회다. 상속 속성 적용이나 effective class 계산은 하지 않는다.
- idref는 이름 문자열 참조라는 기존 relation_kind를 그대로 유지한다. 객체 주입이라고 바꾸지 않는다.

## alias

alias 선언의 member_or_alias는 새 이름, declared_target은 원래 이름이다.
alias chain은 같은 문맥 안에서 추적하고 후보에 alias 원본 파일과 profile 원문을 남긴다.
같은 alias가 여러 대상을 가리키거나 직접 id와 충돌하면 가능한 선언들을 모두 유지한다.
active profile은 평가하지 않으므로 조건부 선언도 보수적으로 남긴다.
한 선언에 여러 경로가 도달하면 대표 alias 경로 하나를 표시한다. 모든 경로의 열거는 아니다.

순환/미해석 alias/탐색 제한은 lookup_warnings에 기록한다.
최대 alias 깊이 64, 조회당 작업 수 4096이며 한계에 도달하면 INCOMPLETE_ALIAS_TRAVERSAL로 표시한다.
부분 후보를 찾았더라도 유일 후보를 모두 탐색했다고 주장하지 않는다.
자기 자신으로의 alias는 재귀적으로 따라가지 않고 SELF_ALIAS_NOT_APPLIED로 표시한다.
동일 문맥/이름/local 제한 조회 결과를 캐시하여 반복 참조 비용을 줄인다.

## CSV와 콘솔

CSV 개수는 **21개**로 유지한다.
기존 spring-bean-references.csv에 결과를 통합하며 별도 후보 CSV를 만들지 않는다.
기존 앞쪽 열은 유지하고 binding_status를 NOT_RESOLVED에서 구체적인 후보 상태로 변경한다.

추가 열:

| 열 | 내용 |
| --- | --- |
| lookup_modeled_context | 이번 행에서 대조한 문맥. 빈 값이면 동일 XML만 대조 |
| candidate_count | 중복 제거된 선언 후보 수 |
| candidate_details | 후보 선언 키, 실제 XML/JAR 위치, 원문 클래스/parent/abstract/factory, 선언 종류, profile, 대표 alias 경로 |
| lookup_warnings | alias 순환/탐색 제한/FactoryBean 접두사 등 미확정 사항 |
| assessment | 한국어 해석 및 한계 |
| binding_certainty | 항상 STATIC_CANDIDATE_ONLY |

candidate_details는 여러 후보를 한 셀의 줄바꿈으로 나열한다.
runtime_call_status는 항상 NOT_EVALUATED다.
후보가 abstract인지, 클래스가 부모에서 상속되는지 등의 원문을 보존할 뿐 인스턴스 생성 가능성은 판단하지 않는다.

콘솔의 Spring Bean Reference Candidates (STATIC)에 참조/문맥 행 수와 상태별 집계를 출력한다.
summary.csv에 bean_reference_context_rows와 상태별 수를 추가한다.
명시 참조 수와 문맥별 결과 행 수는 다를 수 있다. 후보 없음/경고를 Bean XML 파싱 오류에 합산하지 않는다.

## 이후에 남는 분석 범위

실제 Context 계층, 실행 profile, 선택된 alias/override 최종값, Annotation 스캔과 주입,
Java Config, 미지원 namespace 처리기, BeanFactoryPostProcessor, factory 반환 타입은 아직 미확정이다.
따라서 SINGLE은 수집된 같은 문맥 자료에서 후보 1개라는 뜻이지 완전한 정답 보장이 아니다.

## 개발환경 확인

추가 소스: SpringBeanReferenceResolver.java.
수정 소스: SpringBeanAnalyzer.java, SpringBeanReportWriter.java, DependencyReportWriter.java.
기존대로 ExtensionScanner 실행 후 CSV 21개와 콘솔의 새 후보 집계를 확인한다.
spring-bean-references.csv에서 SINGLE/MULTIPLE/NO_SAME_CONTEXT 및 alias 후보 경로를 볼 수 있다.
부모 문맥 미확정이나 후보 없음이 있다고 SAP 설정을 바로 고치지 않는다.

## 검증

SpringBeanReferenceResolverTest는 CORE/WEB 격리, name 별칭, alias chain/분기/순환/상한,
profile 중복 정의, 내부 Bean 전역 노출 방지, 부모 문맥 미확정, 미도달 XML 격리,
FactoryBean 접두사, 동적 이름, 다중 문맥, 3만 건 반복 조회와 CSV를 검증한다.
SpringUtilNamespaceTest는 실제 XML/import/로딩 도달성을 거쳐 util 참조가 연결되는 통합 경로를 검증한다.
기존 회귀 테스트도 함께 실행한다.

규칙 참고(현재 문서의 기본 XML 이름/참조 의미만 참고하며 대상 Spring 전체 구현을 재현하지 않음):
- https://docs.spring.io/spring-framework/reference/core/beans/definition.html
- https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-properties-detailed.html
