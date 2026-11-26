# spring-bean-analyzer-from-xml

SAP Commerce의 Spring XML을 기반으로 Spring 빈과 그 **최종 승자**(같은 이름이 여러 번 정의될 때 실제로 남는 정의)를 추정하는 독립 Java 분석 도구입니다.
예전 이름은 `sap-commerce-analyzer`입니다.
extension에 관한 분석(활성 판정, `requires-extension` 의존관계, 로드 순서)은 모두 [extension-analyzer](../extension-analyzer/README.md)가 하고,
이 도구는 그 결과를 읽어 **Spring XML만** 분석합니다.
Java 21 표준 API만 사용하며 Maven, Gradle, Spring Boot 및 외부 라이브러리는 필요하지 않습니다.
SAP Commerce의 소스와 설정은 읽기만 합니다.

## 구현 범위

- [extension-analyzer](../extension-analyzer/README.md)의 **가장 최근 결과**를 읽어 다음을 사용
  - extension 정의(이름당 사용하는 정의 하나)와 `extensioninfo.xml` 위치
  - 선택 상태(명시 지정·자동 로딩·플랫폼 기본 포함·간접 의존)
  - extension 사이의 **보장된 로드 순서**(`extension-load-order.csv`)
  - 이 도구는 `extensioninfo.xml`을 다시 파싱하지 않고, 의존관계도 다시 계산하지 않습니다.
- 선택된 Extension의 Spring XML 및 import 정방향·역방향 관계 분석
- Spring 로딩 진입점과 XML Bean 선언·명시적 참조의 정적 목록
- 컨텍스트별 빈 이름·alias의 **최종 승자 결정**과 참조의 최종 연결 (「빈 최종 승자 결정」 참고)
- XML 외부 엔티티, 외부 DTD 및 외부 스키마 접근 차단

시각화, 애너테이션 기반 빈(소스 분석) 및 실행 중 메서드 CallTrace는 이 도구의 범위 밖입니다.

## Eclipse에서 가져오기

1. 프로젝트 폴더 전체를 개발 PC로 복사합니다. 위치는 자유롭게 바꿀 수 있습니다.
2. **File → Import → General → Existing Projects into Workspace**를 선택합니다.
3. 이 폴더를 Root directory로 지정하고 프로젝트를 가져옵니다.
4. **Preferences → Java → Installed JREs**에서 JDK 21을 등록합니다.
5. 프로젝트의 **Java Build Path → Libraries**에서 JavaSE-21 연결을 확인합니다.
6. 실행 클래스 `src/execute/Execute.java`(`execute.Execute`)를 선택하고
   **Run As → Java Application**으로 실행합니다.

프로젝트에 Java 21 소스/컴파일 대상과 UTF-8 인코딩 설정을 포함했습니다.
`module-info.java`는 사용하지 않습니다.

## 실행 순서

1. 먼저 [extension-analyzer](../extension-analyzer/README.md)를 실행합니다.
   결과는 `extension-analyzer/reports/yyyyMMdd_HHmmss_고유값/`에 저장됩니다.
2. 이 도구를 실행합니다. 기본으로 `../extension-analyzer/reports`에서 **가장 최근 결과 폴더**를 골라 사용합니다.
   - 고르는 기준: 폴더 이름(`yyyyMMdd_HHmmss_`)이 가장 늦은 것. 이 형식의 폴더가 없으면 `extension-activation.csv`의 수정 시각이 가장 늦은 것.
   - 사용한 결과 폴더는 콘솔 `Extension result:`와 `summary.csv`의 `extension_report`에 남습니다.

extension-analyzer 결과가 없으면 분석하지 않고 종료 코드 1로 끝납니다. 결과에서 다음 파일을 읽습니다.

| 파일 | 사용 내용 |
| --- | --- |
| `summary.csv` | `hybris_home`(분석 루트), `localextensions_file`, 찾은/파싱한 XML 수, `scan_complete` |
| `extension-activation.csv` | 발견한 extension(이름당 사용하는 정의 하나)과 선택 상태·사유·근거, `extensioninfo.xml` 경로 |
| `activation-issues.csv` | 선택 판정 문제 (`selection_complete`) |
| `extensions.csv` | 선택된 extension의 `web_module`(`<webmodule>` 선언 여부)과 `load_order_cycle`(requires/load-prior-to 순환 여부) |
| `extension-load-order.csv` | 선택된 extension 사이의 보장된 순서 쌍. 없는 쌍은 순서 미확정 |
| `extension-duplicates.csv`, `activation-notes.csv`, `scan-failures.csv` | 중복 정의, 참고 기록, 스캔 실패 (없으면 0건으로 처리) |

예전 activated-extension-extractor의 결과에는 `extension-load-order.csv`가 없으므로 **오류로 종료**합니다.
extension-analyzer를 다시 실행하세요.

## 실행 인자

전체 분석은 **Program arguments를 비운 상태로 실행**하면 됩니다. 분석 루트는 extension-analyzer 결과의 `hybris_home`입니다.

**Run → Run Configurations → Java Application → Execute → Arguments → Program arguments**:

```text
--extensions "C:\work\extension-analyzer\reports" "C:\hybris"
```

경로에 공백이 있으면 큰따옴표로 감쌉니다.

- 인자 없음: 기본 위치의 최신 extension-analyzer 결과로 Spring 분석 및 CSV 저장
- `--extensions <폴더>`: extension-analyzer의 `reports` 폴더(최신 결과 자동 선택) 또는 결과 폴더 하나.
  VM arguments의 `-Danalyzer.extensions=<폴더>`로도 지정할 수 있습니다. 기본값은 `../extension-analyzer/reports`(작업 디렉터리 기준)
- 인자 하나: 선택 사항. SAP Commerce 루트. extension-analyzer 결과의 `hybris_home`과 다르면 **오류로 종료**합니다
  (다른 설치의 선택 결과를 섞지 않기 위해서입니다)
- `--help`: 사용법 출력

예전의 `--extractor`, `-Danalyzer.extractor`는 `--extensions`, `-Danalyzer.extensions`로 바뀌었습니다.
특정 extension의 의존 트리 출력(예전의 두 번째 인자)은 extension-analyzer로 옮겼습니다.
다른 `localextensions.xml`로 판정하려면 extension-analyzer를 `-Dextractor.localextensions=<파일>`로 실행한 뒤 그 결과를 사용하세요.

예상 출력 형태:

```text
=== Spring Bean Analyzer (from XML) ===
Extension result: C:\...\extension-analyzer\reports\20261126_101500_...
Hybris root     : C:\hybris

=== Extensions (from extension-analyzer) ===
Definitions     : ...
Selected        : ...
Selection complete: true
Scan complete   : true
Load order cycles: 0

=== Spring XML Import Analysis (SOURCE AND REACHABLE JAR XML) ===
...
=== Spring Bean Final Winners (STATIC REGISTRATION ORDER) ===
...
CSV reports: ...
```

## 전체 분석 결과 파일

기본 저장 위치는 실행 작업 디렉터리의 `reports/yyyyMMdd_HHmmss_고유값/`입니다.
Eclipse의 기본 작업 디렉터리는 보통 프로젝트 폴더이며, 실행 완료 시 출력되는 절대 경로를 확인하세요.
매 실행마다 새 폴더를 만들어 이전 결과를 덮어쓰지 않습니다. `reports/`는 Git에서 제외합니다.

CSV는 모두 **19개**입니다. extension에 관한 CSV(`extensions.csv`, `dependencies.csv`, `reverse-dependencies.csv`,
`missing-dependencies.csv`, `cycles.csv`, `extension-activation.csv`, `activation-issues.csv`, `extension-duplicates.csv`)는
extension-analyzer 결과 폴더에만 있습니다.

| 파일 | 내용 |
| --- | --- |
| `summary.csv` | 사용한 extension-analyzer 결과 폴더(`extension_report`), 스캔·선택 완전성, 선택 수, Spring 분석 단계별 개수 |
| *(카테고리 값 전체)* | 모든 CSV의 상태·종류 열에 들어가는 값과 뜻: `docs/CSV_CATEGORY_REFERENCE.md` |
| `spring-xml-files.csv` | 선택된 Extension에서 조사한 XML, Spring 여부, 파싱 결과, import 수 |
| `spring-imports.csv` | import 출발 XML·줄 번호·원문 resource·대상 후보·해석 상태 |
| `spring-reverse-imports.csv` | 대상 XML을 참조하는 출발 XML 목록 |
| `spring-import-issues.csv` | 검색·파싱 문제, 해석 불가·누락·모호한 참조 |
| `spring-jar-files.csv` | 검색한 JAR, 소유 Extension, 범위, 인덱싱 결과, XML 항목 수 |
| `spring-property-resolutions.csv` | import 속성 표현식의 원문, 해석 경로, 값 선택 근거 및 실패 사유 |
| `spring-jar-xml-files.csv` | 도달한 JAR 내부 XML의 위치·분석 문맥·파싱 상태·import 수 |
| `spring-loading-entrypoints.csv` | 로딩 진입점 선언·규칙과 대상 후보 |
| `spring-loading-reachability.csv` | 진입점별 XML 도달 관계, 이전 XML과 깊이 |
| `spring-loading-issues.csv` | 진입점/로더/도달 관계 해석의 한계 및 문제 |
| `spring-beans.csv`, `spring-bean-references.csv`, `spring-bean-issues.csv` | XML 빈 선언 목록, 명시 참조와 같은 컨텍스트 안의 후보, 목록 작성의 한계 |
| `spring-bean-winners.csv` | 컨텍스트 × 빈 이름별 최종 승자 정의, 판정 상태, 선언/실효 클래스, 밀려난 정의, 순서 미확정 후보 |
| `spring-alias-winners.csv` | 컨텍스트 × alias별 최종 대상, 판정 상태, 최종적으로 가리키는 빈 |
| `spring-bean-registrations.csv` | 등록 재생 순서(sequence)·import 경로·결과(WINNER/OVERRIDDEN 등) |
| `spring-bean-final-references.csv` | ref 등 명시 참조를 alias·부모 컨텍스트까지 따라간 최종 연결 대상 |
| `spring-bean-winner-issues.csv` | 따라가지 못한 진입점·import, import 순환, 순서 순환 등 승자 판정의 한계 |

CSV는 UTF-8 BOM, CRLF, 모든 필드 큰따옴표 및 내부 큰따옴표 이스케이프를 사용합니다.
행이 없는 파일도 헤더를 생성합니다.

저장 위치를 바꾸려면 Program arguments가 아닌 **VM arguments**에 입력하세요.

```text
-Danalyzer.output="C:\analysis-results"
```

## 결과 해석

- 활성 대상은 extension-analyzer의 설정 기반 추정이며 실행 중인 서버 상태와 동일함을 보장하지 않습니다.
- 정상 종료 코드는 0입니다. 다음은 1입니다:
  extension-analyzer 결과 없음 또는 필요한 파일·열 누락 / hybris-root 불일치 /
  extension-analyzer의 스캔 실패(`scan_complete=false`) / 결과에 적힌 `extensioninfo.xml`이 지금은 없음(결과가 오래됨) /
  선택 해석 문제(같은 lookup 경로 안의 중복 포함).
  이 경우에도 가능한 범위에서 CSV는 저장합니다.
- extension-analyzer 결과는 실행 시점의 설치 상태입니다. 설치나 `localextensions.xml`을 바꿨다면 extension-analyzer를 다시 실행하세요.
  이 도구는 `extensioninfo.xml`을 다시 읽지 않으므로, 그 사이 바뀐 extension 이름이나 의존관계는 알아채지 못합니다.

## 소스 구조

```text
src/execute/
└── Execute.java                실행 클래스: 인자 처리, 사용법 출력
src/com/extension/              extension-analyzer 결과를 받는 쪽 (분석 로직 없음)
├── ExtensionReport.java        extension-analyzer 결과 폴더 선택·읽기
├── ExtensionActivation.java    선택 상태·이슈·중복 정의 데이터
├── ExtensionInfo.java          extension 이름과 extensioninfo.xml 위치
└── ExtensionOrder.java         extension-load-order.csv의 보장된 순서 (없는 쌍은 미확정)
src/com/sapcommerce/analyzer/   Spring 분석
├── AnalysisRunner.java         분석 순서 실행(결과 읽기 → Spring 분석 → CSV)
├── AnalysisReportWriter.java   분석 단계 실행과 summary.csv
└── Spring*.java 등             Spring XML·로딩·Bean 분석과 최종 승자
```

Eclipse의 소스 폴더는 그대로 `src` 하나입니다. 패키지만 나뉘었습니다.

소스 및 Eclipse 설정만 제공하며 컴파일 산출물은 포함하지 않습니다.
실제 SAP Commerce 환경에서의 검증은 개발 PC에서 수행하세요.

## extension 정보 (extension-analyzer로 분리)

`extensioninfo.xml` 전체 검색, `localextensions.xml` 기반 선택 판정, `requires-extension` 의존관계·순환 분석,
extension 로드 순서 계산은 [extension-analyzer](../extension-analyzer/README.md)에 있습니다.
판정 규칙과 근거는 extension-analyzer README를 참고하세요.

이 도구는 그 결과를 그대로 사용합니다.

- extension 목록: `extension-activation.csv`에 있는 정의(이름당 사용하는 정의 하나)입니다.
- 선택 상태: Spring XML·JAR·속성 해석의 대상 범위(선택된 extension)와 비선택 보조 검색의 범위를 정합니다.
- 로드 순서: `extension-load-order.csv`의 쌍만 "보장된 순서"로 씁니다. 빈 최종 승자 판정에 사용합니다.
- `summary.csv`의 `selection_complete`는 extension-analyzer의 `activation-issues.csv`가 비어 있는지입니다.
  `scan_complete`는 extension-analyzer의 스캔 완전성과, 적힌 `extensioninfo.xml`이 지금도 있는지를 합친 값입니다.

검증용 소스는 `test/com/extension/ExtensionReportTest.java`입니다.
최신 결과 선택, CSV·로드 순서 읽기, 오래된 결과(파일 없음, 예전 extractor 결과) 검출, hybris-root 불일치, 19개 CSV 출력까지 확인합니다.
판정·의존관계·순서 규칙 자체의 테스트는 extension-analyzer의 `ExtensionAnalyzerTest`에 있습니다.

## Spring XML import 분석

인자 없이 실행하는 흐름에 자동 연결했습니다. 로딩 진입점 확장 후 전체 CSV는 **18개**입니다.
Console의 `Spring XML Import Analysis (SOURCE AND REACHABLE JAR XML)`에서 조사 XML 수, Spring XML 수,
import 선언 수, 결과 행 수 및 문제 수를 확인할 수 있습니다.

수집 범위는 선택 상태가 EXPLICIT/AUTOLOAD/PLATFORM/DEPENDENCY인 Extension의
`resources/`와 `web/webroot/` 아래 XML입니다(`web/webroot/`는 `extensioninfo.xml`에 `<webmodule>`이 선언된 extension만). `.git`, `node_modules`, `temp` 및
심볼릭 링크 대상은 따라가지 않습니다. 기본 Spring namespace의 `<beans>` 루트와
namespace 없는 `<beans>`를 Spring XML로 식별합니다. 그 안의 beans/import만 추출하며,
주석과 다른 namespace의 import는 무시합니다. 프로파일별 중첩 beans의 import도
잠재적 관계로 수집하며 활성 프로파일 판정은 하지 않습니다.

`spring-xml-files.csv`에는 조사한 일반 XML도 포함됩니다. 일반 XML은 루트만 확인하고
NOT_SPRING으로 표시하며 전체 문법을 검증하지 않습니다. Spring XML은 끝까지 읽습니다.
Spring XML 수에는 루트는 식별했으나 이후 파싱에 실패한 파일도 포함됩니다.

| 상태 | 의미 |
| --- | --- |
| `RESOLVED_RELATIVE` | 출발 XML의 디렉터리를 기준으로 조사 범위 내 대상 파일 확인 |
| `RESOLVED_RELATIVE_LEADING_SLASH` | 앞의 `/`를 제외하고 출발 XML 기준 상대 경로로 대상 확인 |
| `RESOLVED_JAR_RELATIVE` | 같은 JAR의 출발 entry 디렉터리를 기준으로 대상 확인 |
| `CLASSPATH_CANDIDATE` | `classpath:`와 일치하는 일반 XML 또는 JAR 내부 후보 1개 |
| `AMBIGUOUS_CLASSPATH` | `classpath:` 후보가 여러 개. 실행 클래스패스의 순서를 알 수 없음 |
| `CLASSPATH_ALL_CANDIDATE` | `classpath*:`에 해당하는 조사 범위 내 후보들 |
| `NOT_FOUND_IN_SCOPE` | 조사 범위에서 해당 XML을 찾지 못함. 실제 리소스 부재를 단정하지 않음 |
| `UNRESOLVED_PROPERTY` | 빈 경로, 해석할 수 없는 속성/충돌/순환 참조 또는 미지원 SpEL 표현식 |
| `UNSUPPORTED_LOCATION` | file/HTTP/JAR URL, UNC 경로 등 실행 ResourceContext가 필요한 위치 |

클래스패스 후보의 루트는 각 Extension의 `resources/`와 같은 Extension의
`web/webroot/WEB-INF/classes/`입니다. 다른 Storefront의 WEB-INF/classes는 섞지 않습니다.
이는 실제 Core/Web Context의 클래스패스를 재현한 것이 아닙니다.
`*`, `?`, `**` 형태의 경로 패턴을 지원합니다. `classpath*:`의 모든 후보를 행으로 출력하며,
`classpath:` 후보가 여러 개여도 하나를 임의로 선택하지 않습니다.
import 대상이 일반 XML이거나 파싱 실패인 경우 target_parse_status 및 문제 목록에 표시합니다.

소스 XML은 한 번, 도달한 JAR XML은 리소스/분석 문맥당 한 번 조사하므로 순환 import에서도 종료됩니다. 수집된 모든 Spring XML의
import를 분석하므로 범위 내 연결은 여러 단계를 따라 확인할 수 있습니다.
이 버전은 각 단계의 직접 간선 목록을 출력하며 전체 경로를 펼친 트리나 순환 그룹은 출력하지 않습니다.
원본 resource가 여러 대상에 매칭될 수 있어 import 선언 수와 결과 행 수는 다를 수 있습니다.
역방향 CSV에는 대상 파일 후보가 있는 행만 포함하고, 미해석 참조는 정방향/문제 CSV에 남깁니다.

**Spring XML 발견 및 import 관계는 실제 로딩의 증거가 아닙니다.** 자동 Spring 설정 수집 규칙,
`web.xml` 진입점, 전체 런타임 properties 재현, 활성 profile 및 Core/Web ApplicationContext 소속은 후속 단계입니다.
일반 XML의 loading_status는 NOT_EVALUATED입니다. 선택된 JAR에서 참조로 도달한 XML은 본문과 import를 분석합니다.
비선택 JAR 후보는 존재만 확인하고 JAR_ENTRY_NOT_PARSED로 표시합니다. 실행 중 생성되는 리소스는
이번 범위에 포함하지 않으므로 NOT_FOUND_IN_SCOPE는 개발 환경에서 검토해야 합니다.
Spring import 문제는 CSV의 진단 정보이며 단독으로 프로세스 종료 코드를 변경하지 않습니다.
Console에도 문제 상태별 건수를 출력합니다.
XML 외부 엔티티와 네트워크 요청을 차단합니다. DOCTYPE 선언만 있는 Spring XML은 외부 DTD를
읽거나 검증하지 않고 본문을 분석하며 `PARSED_DTD_SKIPPED`로 표시합니다.
사용자 정의 엔티티 참조는 확장하지 않고 파싱 문제로 남깁니다.

`/mmmcore/processes/example.xml`과 같은 import는 Spring의 상대 리소스 규칙에 따라 앞의 `/`를
제외하고 출발 파일의 디렉터리를 기준으로 검색합니다. 실제 로딩 Context가 다른 경우의
서블릿 루트/파일시스템 절대경로 해석까지 보장하지 않으며, 대상을 못 찾으면 NOT_FOUND_IN_SCOPE입니다.

정적 리소스 해석 참고:
[Spring Framework Resources](https://docs.spring.io/spring-framework/reference/core/resources.html).
검증용 소스: `test/com/sapcommerce/analyzer/SpringXmlAnalyzerTest.java`.

## JAR 리소스 검색 확장

`Execute`를 실행 인자 없이 실행하면 JAR 검색도 자동 수행합니다.
선택된 Extension의 폴더 전체와 플랫폼 공통 `bin/platform/lib`,
`bin/platform/bootstrap/bin` 아래 JAR에서 XML 항목 이름을 수집합니다.
`.git`, `node_modules`, `temp`와 심볼릭 링크 대상은 제외합니다.
JAR는 디스크에 압축 해제하거나 실행하지 않습니다. 도달한 선택 JAR XML만 스트림으로 읽고
내부 import를 추적합니다. 인덱스에 있는 모든 XML을 무조건 파싱하지는 않습니다.

`classpath:` 및 `classpath*:`는 기존 일반 XML 후보와 JAR XML 후보를 함께 검색합니다.
JAR 상대 경로의 정확한 일치와 `*`, `?`, `**` 패턴을 지원합니다.
다른 Storefront의 web/webroot 아래 JAR는 후보에 섞지 않습니다.
이 범위는 실제 클래스패스 설정을 재현한 것이 아닙니다. 백업/테스트/배포 복사본도
선택된 Extension 아래에 있으면 후보에 들어갈 수 있어 실제 사용 여부를 단정할 수 없습니다.

`spring-imports.csv`와 역방향 CSV에 다음 열을 추가했습니다.

- `target_kind`: SOURCE_FILE 또는 JAR_ENTRY
- `target_jar`: 발견한 JAR의 절대 경로
- `target_entry`: JAR 내부의 XML 경로
- `runtime_classpath_status`: NOT_EVALUATED

JAR 대상을 가리키는 `target_xml`은 `jar:file:/...!/META-INF/example.xml` 형식입니다.
선택된 JAR 대상은 본문 조사 결과인 PARSED/PARSED_DTD_SKIPPED/NOT_SPRING/PARSE_ERROR로 표시합니다.
비선택 후보의 `target_parse_status=JAR_ENTRY_NOT_PARSED`는 존재만 확인했다는 뜻입니다.
`classpath:`에서 여러 후보가 발견되면 AMBIGUOUS_CLASSPATH로 모두 출력합니다.
새로 발견한 JAR 때문에 결과 행 수나 모호성 문제가 늘어날 수도 있습니다.
JAR 읽기 실패는 JAR_INDEX_ERROR로 기록하고 나머지 검색을 계속합니다.

작업 배경, 이전 9건의 해석, 처리 순서, 출력 예시, 검증 및 재실행 확인 방법은
`docs/JAR_RESOURCE_SEARCH.md`에 기록했습니다.
테스트 소스는 `test/com/sapcommerce/analyzer/JarResourceIndexTest.java`입니다.

## 비선택 Extension 보조 검색

선택된 소스/JAR에서 참조를 찾지 못한 경우에만 NOT_SELECTED Extension의 소스와 JAR를
보조 검색합니다. 처음 필요할 때 목록을 한 번 만들고 같은 실행에서 재사용합니다.
선택된 영역에서 이미 찾은 참조에는 비선택 후보를 추가하지 않습니다.
보조 검색의 소스 범위는 resources와 web/webroot 아래 XML, JAR 범위는 해당 Extension
전체입니다. 기존 제외 디렉터리와 심볼릭 링크 미추적 규칙을 유지합니다.

발견 시 `resolution_status=FOUND_IN_UNSELECTED_EXTENSION`으로 표시합니다.
정방향/역방향 CSV의 `target_extension`, `target_selection_status`에서 소유자와 NOT_SELECTED를
확인할 수 있습니다. 기존 활성 판정, 선택된 XML 조사 수 및 import 선언 수는 바뀌지 않습니다.
후보를 여러 개 찾으면 모두 출력하며, 한 선언에 대한 진단은 문제 CSV에 한 건 남깁니다.
실제 파일/JAR 경로는 각 정방향 결과 행에서 확인합니다.

소스 후보도 본문을 파싱하지 않고 SOURCE_FILE_NOT_PARSED로 표시합니다.
JAR는 JAR_ENTRY_NOT_PARSED이며 spring-jar-files.csv의 selection_scope=NOT_SELECTED로 구분합니다.
보조 검색은 실행 가시성이 아닌 존재 여부를 조사하므로 다른 웹 Extension의 후보도 기록할 수 있습니다.
선택된 영역의 일반 클래스패스 검색에 적용하던 다른 Storefront 제외 규칙을 대체하지 않습니다.
UNKNOWN 대상은 NOT_SELECTED로 간주하지 않고 보조 검색에서 제외합니다.

이 진단은 미발견을 설명하는 단서로 문제 목록에 유지합니다. 따라서 NOT_FOUND_IN_SCOPE가
줄어도 Import issues 총수는 같을 수 있습니다. 보조 검색 후에도 못 찾은 항목은
NOT_FOUND_IN_SCOPE로 유지합니다. 접근 실패와 JAR 읽기 실패도 별도 진단으로 남깁니다.
수집된 비선택 XML의 내부 import를 따라가거나 소유 Extension을 활성 대상으로 승격하지 않습니다.
비선택 보조 검색 자체는 CSV를 추가하지 않습니다. 현재 전체 CSV는 로딩 진입점 결과를 포함해 **18개**입니다.

작업 기록: `docs/UNSELECTED_RESOURCE_SEARCH.md`.
검증 소스: `test/com/sapcommerce/analyzer/UnselectedResourceSearchTest.java`.

## import 속성 표현식 해석

`Execute`를 인자 없이 실행하면 `${key:default}` 및 `${key}`를 해석한 뒤
기존 소스/JAR/비선택 보조 검색에 연결합니다. 기본값은 읽은 설정 범위에서 키가 없을 때만 사용합니다.

높은 우선순위부터 다음 파일을 읽습니다.

1. 분석 루트의 config/local.properties
2. 선택된 Extension의 project.properties
3. 분석 루트의 bin/platform/project.properties

같은 키를 여러 선택 Extension에서 다른 값으로 정의하면 순서를 임의로 정하지 않고 미해결로 둡니다.
local.properties에서 해당 키를 정의하면 Extension 충돌보다 우선합니다.
여러 Extension의 값이 동일하면 같은 값으로 처리하되 근거 파일을 모두 남깁니다.
키의 값이 빈 문자열인 경우 기본값으로 대체하지 않습니다. 최종 import 경로가 빈 경우는 미해결입니다.
중첩/다중 표현식과 설정값 안의 표현식을 재귀 해석하며 순환 참조와 과도한 깊이를 차단합니다.
파일 읽기/문법 오류는 PROPERTY_FILE_ERROR로 남기고 표현식을 임의 기본값으로 처리하지 않습니다.
UNKNOWN 등으로 Extension 선택이 불완전하면 local.properties에 없는 키의 하위 설정/기본값 선택은 보류합니다.

정방향/역방향 CSV의 resource는 원문을 유지하고 아래 열을 추가합니다.

- resolved_resource: 실제 검색에 사용한 경로
- property_status: LITERAL, PROPERTY, DEFAULT, PROPERTY_AND_DEFAULT 또는 UNRESOLVED
- property_evidence: 사용한 키와 정의 파일 또는 XML_DEFAULT

spring-property-resolutions.csv는 표현식이 있는 선언과 해석 실패만 기록합니다.
참조하지 않은 일반 properties 키/값 전체를 출력하지 않습니다.
값이 해석됐어도 대상 파일 검색에 성공했다는 뜻은 아닙니다. resolution_status를 함께 확인하세요.

properties는 Java Properties.load(InputStream)의 ISO-8859-1/Unicode escape 규칙을 사용합니다.
분석 PC의 JVM 시스템 속성/환경변수를 SAP 서버의 값으로 간주하지 않습니다.
Spring의 커스텀 PropertySource, 서버 실행 중 변경, HYBRIS_RUNTIME_PROPERTIES, optional config,
임의 추가 properties 파일은 이번 모델에 자동 병합하지 않습니다. SpEL과 이스케이프된 표현식도 평가하지 않습니다.
따라서 결과는 지정 파일 범위의 정적 추정입니다. runtime 검증 상태는 계속 NOT_EVALUATED입니다.
필요할 경우 VM arguments의 `-Danalyzer.localproperties="C:\config-copy\local.properties"`로
분석할 local.properties 복사본을 지정할 수 있습니다. 원본 설정을 수정할 필요는 없습니다.

기록: `docs/IMPORT_PROPERTY_RESOLUTION.md`.
검증: `test/com/sapcommerce/analyzer/ImportPropertyResolverTest.java`.

## 선언·주석만 있는 XML

widgets 설정 전체가 주석 처리된 파일처럼 루트 요소가 없는 파일은
`SKIPPED_NO_ROOT_ELEMENT`로 구분합니다. 이는 정상 XML 파싱 성공을 뜻하지 않으며,
단독 XML 문서로는 루트 요소가 없는 상태임을 명시한 제외 사유입니다.
파일 목록과 문제 목록에 기록하고 Spring XML/import 분석에서는 제외합니다.
원본 파일은 수정하지 않습니다. 따라서 문제 총수가 그대로여도 XML_INSPECTION_ERROR가
SKIPPED_NO_ROOT_ELEMENT로 바뀌었다면 재분류된 것입니다.

주석만 있는지 추가 검증한 경우에만 분류하며, 깨진 주석·태그·일반 텍스트 오류는 유지합니다.
재검증은 1MB 이하 파일에만 적용하고 그보다 큰 실패는 기존 오류로 남깁니다.
콘솔과 summary.csv에 제외 건수를 추가했습니다. 이 분류는 별도 CSV를 추가하지 않습니다.
작업 기록: `docs/ROOTLESS_XML_CLASSIFICATION.md`.

## 도달한 JAR XML 본문과 후속 import 추적

실행 방법은 같습니다. Execute를 인자 없이 실행합니다.
ZipFile.getInputStream으로 JAR 내부의 XML을 읽고 기존과 같은 namespace/import 및 속성 해석 규칙을 적용합니다.
디스크에 XML을 추출하지 않습니다. 상대 import는 같은 JAR의 출발 entry 디렉터리에서 찾고,
클래스패스 import는 선택된 소스/JAR에서 찾습니다. 미발견 클래스패스는 비선택 보조 검색에도 연결하지만,
그곳에서 발견한 XML의 본문/후속 import는 분석하지 않습니다.

작업 대기열에 새 import를 추가하고 각 JAR 경로+entry+분석 문맥 Extension을 캐시합니다.
순환 참조를 따라 무한 재귀하지 않으며 반복 간선 자체는 결과에 유지합니다.
분석 문맥은 최초 출발 소스의 Extension을 사용한 정적 가시성 기준이며 실제 ApplicationContext ID가 아닙니다.
소스 XML은 기존처럼 소유 Extension 문맥으로 먼저 분석합니다.

새 CSV `spring-jar-xml-files.csv`에서 JAR/entry/문맥별 파싱 상태와 import 수를 확인합니다.
정방향/역방향 CSV의 source_xml도 JAR 내부이면 jar:file:/...!/entry.xml 형식이며,
source_context_extension으로 분석 문맥을 구분합니다. 속성 해석 CSV에도 같은 문맥을 기록합니다.
JAR XML이 깨졌거나 사용자 정의 엔티티가 있으면 JAR_XML_INSPECTION_ERROR를 기록합니다.
외부 DTD/엔티티/네트워크 로딩은 차단하고 DOCTYPE 선언만 있는 파일은 DTD를 생략하고 읽습니다.
JAR XML 1개는 압축 해제 기준 최대 16MiB까지 읽으며 초과하면 진단으로 남깁니다.

기존 Inspected XML/Spring XML은 소스 기준을 유지합니다. Import declarations는 소스+JAR 선언 합계이며
Source import declarations와 JAR import declarations를 별도로 출력합니다.
JAR XML parsed contexts는 실패·비Spring 포함 조사 건수, JAR XML unique resources는 중복 문맥을 제외한 리소스 수입니다.
같은 entry를 여러 문맥에서 분석하면 선언과 행 수도 문맥별로 증가할 수 있습니다.
Runtime 로딩은 여전히 NOT_EVALUATED이고 모호한 클래스패스 후보도 확정하지 않습니다.

기록: `docs/JAR_XML_TRAVERSAL.md`.
검증: `test/com/sapcommerce/analyzer/JarXmlTraversalTest.java`.

## Spring 로딩 진입점과 잠재 도달 관계

Execute를 인자 없이 실행하면 import 분석 후
`Spring Loading Entry Analysis (STATIC CANDIDATES)`를 출력합니다. 전체 CSV는 18개입니다.

선택된 Extension에서 다음 근거를 수집합니다.

- `<extension>.application-context` 속성: Core 설정 후보 (값은 쉼표/공백/세미콜론으로 구분)
- `<extension>.global-context` 속성: Global 설정 후보
- 명시 application-context 속성이 없고 resources/<extension>-application-context.xml이 존재하면 관례 후보
- web/webroot/WEB-INF/web.xml의 context-param/contextConfigLocation: 웹 루트 후보. `<webmodule>`이 선언된 extension만 대상이며, 선언 없이 web.xml만 있으면 `WEB_XML_WITHOUT_WEBMODULE`로 기록 ([배경](../extension-analyzer/docs/background_knowledge_webmodule.md))
- Servlet의 init-param/contextConfigLocation: 별도 Servlet 후보
- 표준 Listener/DispatcherServlet의 XML 설정 기본 경로: 기본 규칙에 따른 후보
- web.xml의 display-name 기반 additionalWebSpringConfigs 및 하위 키: SAP 웹 추가 설정 후보

Core 설정의 접두사 없는 값은 우선 classpath로 검색합니다. 파일시스템 fallback 전체는 재현하지 않습니다.
웹의 접두사 없는 경로는 webroot 기준으로 검색합니다.
진입점이 JAR만 가리키는 경우에도 해당 XML 본문과 후속 import를 조사합니다.
진입점은 XML import 선언이 아니므로 기존 import 선언 수에 섞지 않습니다.
다만 진입점 때문에 새로 도달한 JAR의 실제 import는 선언 수에 추가될 수 있습니다.

`spring-loading-entrypoints.csv`는 설정이 어디에 선언됐는지, loader 이름, modeled_context,
evidence_kind, 경로 매칭 및 대상 파싱 상태를 담습니다. 선언된 설정과 관례 추론을 구분합니다.
추정 Context ID는 CORE, GLOBAL, WEB:<extension>, SERVLET:<extension>:<servlet-name>이며
실제 tenant별 ApplicationContext ID나 부모/자식 연결을 확인한 결과가 아닙니다.

`spring-loading-reachability.csv`는 각 진입점에서 import를 따라 도달할 수 있는 XML을 기록합니다.
depth=0은 시작 XML, parent_location은 직전 XML 또는 진입점 선언 근거입니다.
순환 시 같은 진입점/리소스/분석 문맥은 한 번만 기록합니다. 하나의 대표 도달 경로만 보존하며
모든 경로나 실제 로딩 순서를 나열한 것은 아닙니다.
모호한 classpath 후보도 잠재 경로로 유지하며 확정된 로딩으로 표시하지 않습니다.
비선택 진단 후보, 파싱 실패 및 미해석 참조는 도달 경로를 계속 확장하지 않습니다.

`spring-loading-issues.csv`에는 커스텀 loader 미검증, 비XML Context, 미해석 설정,
시작 XML 미발견 및 경로 확장 중단 등을 남깁니다.
*-spring.xml이 있어도 명시 속성 또는 지원하는 관례로 등록 근거를 찾지 못하면
REGISTRATION_NOT_ESTABLISHED로 기록하고 임의 로딩 진입점으로 삼지 않습니다.

이 분석은 **설정 선언과 규칙에 근거한 로딩 후보/잠재 도달성**입니다.
실제 서버 로딩, Listener 구현, 활성 Spring profile, Context 부모/자식 관계, Bean 최종 등록을
검증하지 않았습니다. 모든 runtime_loading_status는 NOT_EVALUATED로 유지합니다.
소스별 분석 문맥은 기존 소유 Extension 기준이고 실제 Core/Web 클래스패스와 같다고 보장하지 않습니다.
Spring Java Config/코드에 의한 동적 등록 및 전체 서버 속성 계층도 재현하지 않습니다.

상세 기록: `docs/SPRING_LOADING_ENTRYPOINTS.md`.
검증: `test/com/sapcommerce/analyzer/SpringLoadingAnalyzerTest.java`.

미발견 진입점에 대해 다른 진입점에서 같은 파일명의 XML이 발견·파싱되면,
`spring-loading-issues.csv`의 `related_entries`와 `assessment` 열에 관련 설정·컨텍스트·실제 경로를 함께 표시합니다.
같은 파일명의 참고 후보이며 원래 미발견 상태, CORE/WEB 구분, 실제 로딩 미검증 상태는 유지합니다.
`spring-loading-entrypoints.csv`에도 같은 관련 정보가 추가됩니다. 이 관련 정보 기능 자체는 CSV를 추가하지 않습니다.
상세 설명과 확인 절차: `docs/RELATED_LOADING_ENTRIES.md`.

## XML Bean 선언과 명시적 참조 목록

Execute의 기본 실행에 Bean 목록 작성을 추가했습니다. 현재 CSV 총수는 **22개**입니다(`extension-duplicates.csv` 포함).

| 신규 파일 | 내용 |
| --- | --- |
| spring-beans.csv | XML의 Bean 원문 이름/클래스/부모/factory/profile 및 잠재 진입점·컨텍스트 |
| spring-bean-references.csv | property/constructor/ref/alias/parent/factory 등의 명시 참조 |
| spring-bean-issues.csv | 파싱 실패, custom namespace 등 미해석 범위 |

Bean 참조는 실제 메서드 호출이 아닙니다. Annotation 스캔과 최종 Bean 선택/연결은 아직 처리하지 않습니다.
자세한 목적, 범위, 결과 해석과 검증: `docs/BEAN_INVENTORY.md`.

## util 네임스페이스 지원

util:list/set/map/constant를 선언 목록에 포함하고 내부 명시 참조를 해당 선언에 연결합니다.
spring-beans.csv 끝에 declaration_kind와 declared_namespace_attributes 열을 추가했습니다.
미지원 범위는 Annotation/Component scan/Context/AOP/Integration/기타로 구분합니다.
콘솔에서 Util declarations 및 상태별 이슈 수를 확인할 수 있습니다. CSV 총수는 22개입니다.
최종 대상 바인딩과 실제 등록/호출은 계속 미검증입니다.
상세 기록과 검증 절차: docs/UTIL_NAMESPACE_SUPPORT.md.
## Bean 참조 선언 후보 연결

SpringBeanReferenceResolver를 추가했습니다. id/name 및 alias chain을 같은 모델링 컨텍스트 안에서 대조합니다.
spring-bean-references.csv의 binding_status에 후보 1개/여러 개/없음 등을 구분하고,
lookup_modeled_context, candidate_count, candidate_details, lookup_warnings, assessment, binding_certainty 열을 추가했습니다.
CSV는 22개이며 참조는 문맥별 행으로 확장될 수 있습니다.
후보 1개도 실제 바인딩 확정은 아닙니다. 부모 Context, profile/override 최종 선택은 미확정입니다.
상세 목적·조건별 결과·검증: docs/BEAN_REFERENCE_CANDIDATES.md.
## 빈 최종 승자 결정

같은 빈 이름이나 alias가 여러 번 정의될 때 컨텍스트(CORE/GLOBAL/WEB/SERVLET)마다 실제로 남는 정의를 정합니다.
CSV 5개가 추가되었습니다. extension CSV를 extension-analyzer로 옮긴 뒤 전체는 **19개**입니다.

- Spring의 등록 순서를 재생합니다: 진입점 순서 → 문서 순서, `<import>`는 그 위치에서 대상 문서를 처리합니다.
- extension 사이의 순서는 extension-analyzer의 `extension-load-order.csv`(requires-extension 전이, `bin/platform/ext` 우선, `<meta key="load-prior-to">`)에 있는 **보장된 쌍만** 씁니다.
  관계가 없는 extension끼리 같은 이름을 정의하면 승자를 고르지 않고 `UNDETERMINED_ORDER`로 후보를 모두 보여 줍니다.
- `additionalWebSpringConfigs`(addon)는 `web.xml`의 설정 뒤에 로드되는 것으로 봅니다.
- 상태: `CONFIRMED` / `UNDETERMINED_ORDER` / `PROFILE_DEPENDENT` / `RESOURCE_CHOICE_UNCERTAIN`
- 참조는 alias를 먼저 따르고, 없으면 부모 컨텍스트(SERVLET → WEB → CORE → GLOBAL, SAP 표준 구성 가정)에서 찾습니다.
- `load-prior-to` meta 해석과 순서 계산은 extension-analyzer가 합니다.

상세 규칙·근거·한계: docs/BEAN_FINAL_WINNERS.md. 검증: `test/com/sapcommerce/analyzer/SpringBeanWinnerResolverTest.java`.
