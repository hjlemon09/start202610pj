# Spring 로딩 진입점과 import 도달 관계 작업 기록

## 시작 시점

사용자의 직전 실행에서 소스 import 276개, JAR import 4개, 전체 선언/결과 행 280개,
고유 JAR XML 9개 및 JAR 파싱 오류 0개를 확인했다.
진단 3건은 비선택 Extension에서 발견한 참조 2건과 루트 요소 없는 XML 제외 1건이었다.

지금까지의 그래프는 “A.xml이 B.xml을 import한다”는 관계였다.
하지만 이 정보만으로는 A.xml 자체를 누가 초기 설정으로 읽는지 알 수 없다.
이번 작업은 설정 진입점을 수집하고 기존 import 그래프와 연결한다.

## 수집하는 근거

| 근거 | 후보 종류 | 근거 구분 |
| --- | --- | --- |
| ext.application-context | CORE | DECLARED_PROPERTY |
| ext.global-context | GLOBAL | DECLARED_PROPERTY |
| ext-application-context.xml 기본 관례 | CORE | CONVENTION |
| web.xml의 context-param/contextConfigLocation | WEB:ext | DECLARED_WEB_XML 또는 DECLARED_UNVERIFIED_LOADER |
| Servlet init-param/contextConfigLocation | SERVLET:ext:name | DECLARED_WEB_XML 또는 DECLARED_UNVERIFIED_LOADER |
| 표준 Spring 웹 XML 기본 경로 | WEB 또는 SERVLET | SPRING_DEFAULT_INFERRED |
| display-name.additionalWebSpringConfigs[.suffix] | WEB:ext | SAP_WEB_PROPERTY_INFERRED 또는 DECLARED_UNVERIFIED_LOADER |

속성값은 기존 정적 properties 우선순위와 충돌 처리를 사용하고 근거 파일을 남긴다.
빈 설정을 기본 경로로 몰래 대체하지 않는다. contextClass가 XML Context가 아니면
NON_XML_CONTEXT로 기록한다. web.xml의 namespace를 인식하고 외부 DTD/엔티티 로딩을 차단한다.

*-spring.xml이 있다는 사실만으로 로딩 시작점이라고 판단하지 않는다.
명시 속성이나 지원하는 default 관례를 찾지 못한 파일은 REGISTRATION_NOT_ESTABLISHED로 남긴다.
실제 프로젝트가 코드를 통해 로딩하는지는 추가 조사할 부분이다.

## 경로 연결

Core/Global 속성의 접두사 없는 경로는 classpath 후보로 검색한다.
웹 설정의 접두사 없는 경로는 webroot 기준으로 계산한다.
classpath/classpath*와 패턴, 일반 소스 및 JAR 후보는 기존 리소스 해석기를 재사용한다.
파일시스템 fallback, Java Config 및 동적 loader 전체를 재현하지 않는다.

진입점이 가리키는 JAR XML도 파싱 대기열에 연결한다. 따라서 소스 XML의 import에는 없고
추가 웹 설정 속성으로만 지정된 JAR XML도 조사할 수 있다.
진입점 선언을 일반 import 간선으로 섞지 않아 source import 수는 기존 의미를 유지한다.
새로 도달한 JAR 본문의 import는 실제 선언이므로 전체 import 수가 늘어날 수 있다.

## 잠재 도달성

각 진입점에서 파싱된 대상 XML을 시작으로 기존 import 그래프를 너비 우선으로 따라간다.
각 행에 시작 entry_id, 추정 context, XML 위치, 깊이, 바로 앞 위치 및 추론 근거를 남긴다.
같은 진입점에서 같은 리소스/분석 문맥에 다시 도달하면 중복 확장하지 않아 순환을 종료한다.
모든 경로를 펼친 것이 아니라 대표 도달 경로 하나를 기록한다.

모호한 classpath 후보는 전부 잠재 대상으로 유지한다. 둘 중 실제로 어떤 것이 선택되는지는
확정하지 않는다. 비선택 후보·파싱 실패·미해석 참조에서는 확장을 멈추고 문제를 기록한다.

CORE, GLOBAL, WEB:ext, SERVLET:ext:name은 보고서용 모델 식별자다.
실제 tenant ID, ApplicationContext 인스턴스 또는 Context 부모/자식 관계를 확인한 값이 아니다.
소스 XML은 기존 소유 Extension 문맥으로 분석하고 JAR에는 출발 Extension 문맥을 전파한다.
이는 실제 클래스패스/Context 가시성의 완전한 재현이 아니다.

## CSV 3개 추가: 전체 18개

1. spring-loading-entrypoints.csv
   - 선언한 파일 또는 속성 키, loader, 설정 경로, 대상, 매칭/파싱 상태
   - evidence_kind와 property_evidence로 선언 근거와 추론 근거 구분
2. spring-loading-reachability.csv
   - entry_id별 XML 도달 관계, depth, parent_location
   - basis=POTENTIAL_REACHABILITY, runtime_loading_status=NOT_EVALUATED
3. spring-loading-issues.csv
   - 설정 해석 실패, 미검증 loader, 비XML Context, 시작 파일 미발견, 도달 확장 중단

Console에는 Spring Loading Entry Analysis (STATIC CANDIDATES)를 추가한다.
Entry declarations는 설정 경로 토큰 수, Entry target rows는 후보별 매칭 결과 행 수다.
Reachability rows는 진입점별 도달 행 수이고 Reachable unique XML은 진입점 중복을 제외한 XML 수다.
Loading issues는 문제뿐 아니라 해석 한계/미검증 사항도 포함한다.
따라서 숫자가 0이 되어야 성공인 것은 아니다.

## 검증

Java 21에서 가상의 Extension, web.xml, properties와 JAR를 만들어 확인했다.

- Core/Global 명시 속성과 default 관례 구분
- namespace가 있는 web.xml, 여러 웹 루트 설정, 별도 DispatcherServlet 설정
- display-name 기반 추가 웹 설정
- 커스텀/부재 loader의 불확실성 표시
- 등록 근거 없는 Spring 파일을 자동 로딩 대상으로 삼지 않음
- 비선택 후보를 잠재 활성 경로로 승격하지 않음
- import가 아닌 진입점에서만 가리키는 JAR의 본문/후속 import 분석
- 순환 도달 관계 종료, XML import 선언 수와 진입점 수 분리
- CSV의 잠재 도달성/실행 미검증 표시
- 기존 분석 기능 회귀 검증

검증 코드: test/com/sapcommerce/analyzer/SpringLoadingAnalyzerTest.java.
실제 개발 환경의 로딩 시작점과 미검증 loader 분포는 반영 후 재실행으로 확인한다.

## 다음 검증에서 볼 것

ExtensionScanner를 Program arguments 없이 실행한다. CSV 18개 생성 여부와 새 콘솔 영역을 확인한다.
Loading issues가 있으면 상태별 수를 먼저 보고 필요한 선언/파일만 함께 조사한다.
실제 로딩됐다고 단정하지 않고, 시작점의 근거와 XML 도달 경로를 원본 설정과 비교한다.

## 참고한 공식 문서

- SAP Spring Context 구성: https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/8c63621986691014a7e0a18695d7d410.html
- SAP 웹 추가 설정: https://help.sap.com/doc/02d5152884b34821a06408495ba0b771/1905/en-US/de/hybris/platform/spring/HybrisContextLoaderListener.html
- Spring 웹 Context: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/context-hierarchy.html

공식 규칙을 정적 모델로 적용했으며 대상 설치 버전의 실제 초기화 구현/서버 로그로 검증한 것은 아니다.
