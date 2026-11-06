# SAP Commerce Analyzer

첨부 문서의 STEP 10-2에서 전체 Extension 의존관계 분석 및 CSV 출력까지 확장한 독립 Java 분석 도구입니다.
Java 21 표준 API만 사용하며 Maven, Gradle, Spring Boot 및 외부 라이브러리는 필요하지 않습니다.
SAP Commerce의 소스와 설정은 읽기만 합니다.

## 구현 범위

- 지정한 루트 아래의 `extensioninfo.xml` 검색
- `node_modules`, `.git`, `temp` 디렉터리 제외 및 접근 실패 시 경고 후 계속 검색
- Extension 이름과 직접 `requires-extension` 목록 파싱
- 검색/파싱 성공/실패/이름 중복/접근 실패 수 출력
- 특정 Extension에서 시작하는 직접·간접 의존관계 트리 출력
- 실행 인자 없이 전체 Extension의 직접·역방향 의존관계, 누락 참조, 순환 그룹 분석 및 CSV 저장
- `config/localextensions.xml`을 분석하여 명시 지정·자동 로딩·플랫폼 기본 포함·간접 의존 대상 구분
- 순환 참조와 이미 방문한 공유 의존관계 구분
- XML 외부 엔티티, 외부 DTD 및 외부 스키마 접근 차단

Spring XML import, ApplicationContext, Bean 분석,
시각화 및 CallTrace는 이후 단계이며 이 버전에 포함하지 않습니다.

## Eclipse에서 가져오기

1. 프로젝트 폴더 전체를 개발 PC로 복사합니다. 위치는 자유롭게 바꿀 수 있습니다.
2. **File → Import → General → Existing Projects into Workspace**를 선택합니다.
3. 이 폴더를 Root directory로 지정하고 프로젝트를 가져옵니다.
4. **Preferences → Java → Installed JREs**에서 JDK 21을 등록합니다.
5. 프로젝트의 **Java Build Path → Libraries**에서 JavaSE-21 연결을 확인합니다.
6. `src/com/sapcommerce/analyzer/ExtensionScanner.java`를 선택하고
   **Run As → Java Application**으로 실행합니다.

프로젝트에 Java 21 소스/컴파일 대상과 UTF-8 인코딩 설정을 포함했습니다.
`module-info.java`는 사용하지 않습니다.

## 실행 인자

전체 분석은 **Program arguments를 비운 상태로 실행**하면 됩니다. 기본 분석 루트는 `C:\hybris`입니다.
경로만 바꾸려면 첫 번째 인자만 입력하세요. 다음 예시는 특정 Extension의 트리도 추가로 보고 싶은 경우입니다.

**Run → Run Configurations → Java Application → ExtensionScanner → Arguments → Program arguments**:

```text
"C:\hybris" mmmcore
```

`mmmcore`는 익명화된 예시입니다. 실제 `extensioninfo.xml`의 `extension name`으로 바꾸세요.
경로에 공백이 있으면 큰따옴표로 감쌉니다.

- 인자 없음: 기본 경로 `C:\hybris` 전체 검색·파싱·의존관계 분석 및 CSV 저장
- 첫 번째 인자: 검색할 SAP Commerce 루트
- 두 번째 인자: 선택 사항. 지정한 Extension의 트리를 추가 출력 (대소문자 구분)
- `--help`: 사용법 출력

예상 출력 형태:

```text
=== Analysis Summary ===
Found XML files : ...
Parsed          : ...
Failed          : 0
Unique names    : ...
Duplicate files : 0
Access failures : 0

=== Full Dependency Analysis ===
Dependency edges: ...
Missing edges   : ...
Cycle groups    : ...
CSV reports: ...

=== Extension Dependency Analysis ===
=== Dependency Tree ===
mmmcore
  -> acceleratorcms
    -> ...
```

위 트리는 두 번째 인자를 입력했을 때만 추가 출력됩니다.
기본 실행에는 `Analysis Summary` 다음에 `Full Dependency Analysis`와 CSV 저장 경로가 출력됩니다.

## 전체 분석 결과 파일

기본 저장 위치는 실행 작업 디렉터리의 `reports/yyyyMMdd_HHmmss_고유값/`입니다.
Eclipse의 기본 작업 디렉터리는 보통 프로젝트 폴더이며, 실행 완료 시 출력되는 절대 경로를 확인하세요.
매 실행마다 새 폴더를 만들어 이전 결과를 덮어쓰지 않습니다. `reports/`는 Git에서 제외합니다.

| 파일 | 내용 |
| --- | --- |
| `summary.csv` | 검색·파싱·접근 실패, Extension·의존관계·누락·순환 그룹 수, 스캔 완전성 |
| `extensions.csv` | 모든 Extension의 XML 경로, 직접 의존 수, 자신을 참조하는 Extension 수, 누락 수, 순환 그룹 |
| `dependencies.csv` | 출발 Extension → 직접 의존 Extension, 대상 발견 여부, 순환 그룹 |
| `reverse-dependencies.csv` | 의존 대상 → 이를 사용하는 Extension. 누락 대상도 포함 |
| `missing-dependencies.csv` | 찾지 못한 대상과 이를 참조한 Extension 및 XML 경로 |
| `cycles.csv` | 순환 그룹 번호별 소속 Extension 및 XML 경로 |
| `extension-activation.csv` | 모든 발견된 Extension의 설정 기반 선택 상태와 사유·선택 근거 |
| `activation-issues.csv` | 설정 파일·지정 이름·경로·의존관계 해석 문제 |

CSV는 UTF-8 BOM, CRLF, 모든 필드 큰따옴표 및 내부 큰따옴표 이스케이프를 사용합니다.
의존관계가 없거나 순환·누락이 없는 파일도 헤더를 생성합니다.
각 목록은 이름순으로 정렬하므로 실행 간 비교할 수 있습니다.
`extensions.csv`에는 의존하거나 참조하는 대상이 없는 Extension도 포함됩니다.

저장 위치를 바꾸려면 Program arguments가 아닌 **VM arguments**에 입력하세요.

```text
-Danalyzer.output="C:\analysis-results"
```

순환 그룹은 강하게 연결된 컴포넌트(SCC)입니다. 그룹 안의 모든 Extension은 서로 도달할 수 있습니다.
자기 자신만 참조하는 경우도 순환으로 검출합니다. `cycles.csv`의 이름순 나열은 호출 순서나
하나의 순환 경로가 아닙니다. 그룹 내부의 실제 간선은 `dependencies.csv`에서 확인합니다.
`cycle_group=0`은 검출된 순환 그룹에 해당하지 않는다는 뜻입니다.
전체 CSV의 관계는 직접 의존관계이며, 모든 간접 경로를 펼쳐서 저장하지 않습니다.

단일 XML만 확인하려면 `ExtensionInfoParser`를 실행하고 Program arguments에
`"C:\hybris\bin\custom\mmm\mmmcore\extensioninfo.xml"`을 입력합니다.

## 결과 해석

- 발견 개수는 XML 파일 수이며 활성 Extension 수가 아닙니다. 622개로 고정하지 않습니다.
  활성 대상은 별도의 설정 기반 추정으로 표시하며 실행 중인 서버 상태와 동일함을 보장하지 않습니다.
- 트리는 XML에 선언된 Extension 의존관계이며 실제 메서드 호출이나 Bean 주입을 의미하지 않습니다.
- `[NOT FOUND]`: 검색·파싱된 Extension 목록에 없음. 검색 범위 밖, 제외 디렉터리,
  파싱 실패 등일 수 있으며 실제 Extension 부재를 단정하지 않습니다.
- `[ALREADY VISITED]`: 앞에서 분석한 Extension. 하위 트리의 중복 출력을 생략합니다.
- `[CYCLE]`: 현재 탐색 경로에 있는 Extension을 다시 참조합니다.
- 동일 이름의 XML이 여러 개면 경로를 출력하고 트리 분석을 중단합니다.
  복사본이나 템플릿이 있는지 확인하고 분석 루트를 좁히세요. 임의의 정의를 선택하지 않습니다.
  이 경우 전체 의존관계 분석과 CSV 저장도 생략합니다.
- 파싱 또는 접근 실패가 있으면 결과가 불완전할 수 있습니다. 정상 종료 코드는 0,
  검색·파싱 오류/중복/시작 Extension 누락은 1입니다. 하위 의존관계의 `[NOT FOUND]`는
  표시만 하며 단독으로 종료 코드를 변경하지 않습니다.
- 전체 분석의 누락 참조와 순환 그룹도 단독으로 종료 코드를 변경하지 않습니다.
  파싱·접근 실패 시 가능한 범위에서 CSV를 저장하고 `scan_complete=false`로 표시합니다.
- 활성 대상 판정 문제가 있으면 CSV는 저장하고 종료 코드는 1입니다.
- 결과는 발견된 정의를 기준으로 합니다. 비활성 Extension이나 템플릿이 포함될 수 있어,
  검출된 순환이 실행 환경의 실제 순환이라고 단정할 수 없습니다.
- 심볼릭 링크를 따라가지 않습니다. `temp` 등 제외 경로의 정의는 검색하지 않습니다.
- DOCTYPE가 있는 XML은 이 버전의 파서에서 거부합니다.

## 소스 구조

```text
src/com/sapcommerce/analyzer/
├── ExtensionScanner.java
├── ExtensionInfoParser.java
├── ExtensionInfo.java
├── ExtensionDependencyAnalyzer.java
├── DependencyReportWriter.java
└── ActiveExtensionAnalyzer.java
```

소스 및 Eclipse 설정만 제공하며 컴파일 산출물은 포함하지 않습니다.
실제 SAP Commerce 환경에서의 검증은 개발 PC에서 수행하세요.

## localextensions.xml 기반 활성 대상 추정

기존처럼 **Program arguments 없이 실행**합니다. 기본 파일은
`C:\hybris\config\localextensions.xml`입니다. 다른 루트를 첫 인자로 주면 그 루트의
`config/localextensions.xml`을 읽습니다. 기존 파일은 수정하지 않습니다.

다른 설정 파일을 분석하려면 VM arguments에서 지정할 수 있습니다.

```text
-Danalyzer.localextensions="C:\config-copy\localextensions.xml"
```

Console에는 `Extension Selection Analysis (STATIC INFERENCE)`가 추가됩니다.
Selected는 다음 네 상태의 합이며, Extension별로 하나의 주된 선택 근거를 기록합니다.

| 상태 | 의미 |
| --- | --- |
| `EXPLICIT` | `<extension name="...">` 또는 `<extension dir="...">`로 지정 |
| `AUTOLOAD` | `<path autoload="true">` 범위에서 발견 |
| `PLATFORM` | 기본 `bin/platform/ext` 자동 포함 |
| `DEPENDENCY` | 선택된 Extension의 `requires-extension`을 재귀적으로 따라 포함 |
| `NOT_SELECTED` | 해석 가능한 설정·스캔 범위에서 선택되지 않음 |
| `UNKNOWN` | 설정/스캔이 불완전해서 선택 여부를 판단할 수 없음 |

이미 확인한 선택 근거는 판정 문제가 있어도 유지하며, 선택되지 않은 나머지를 UNKNOWN으로 표시합니다.
`NOT_SELECTED`는 실행 서버의 비활성 상태를 확인했다는 뜻이 아닙니다.
직접 지정이 자동 로딩보다 우선하며, 여러 의존 경로가 있으면 최초 선택 근거 하나를 기록합니다.
전체 의존 간선은 기존 `dependencies.csv`에서 확인합니다.

처리하는 규칙:

- `name`은 검색된 정의와 lookup 경로를 함께 확인합니다.
- `dir`은 해당 폴더의 `extensioninfo.xml`과 대조합니다. name도 있으면 일치 여부를 확인합니다.
- `<path>`는 기본 lookup 역할이며 `autoload=true`인 경우에만 그 범위 전체를 선택합니다.
- `depth` 기본값은 10입니다. 지정 폴더 자체는 깊이 0, 직접 하위 Extension 폴더는 깊이 1로 계산합니다.
- `<extensions autoload="false">`이면 플랫폼 기본 자동 포함을 끕니다.
- `${HYBRIS_BIN_DIR}`, `${HYBRIS_CONFIG_DIR}`, `${HYBRIS_PLATFORM_DIR}`를 분석 루트 기준으로 확장합니다.
- 필수 의존 대상은 지정 lookup 경로에서 확인되어야 합니다. 이미 명시 지정된 대상도 사용할 수 있습니다.
- XML 주석은 무시하고, 순환 의존관계에서도 탐색을 종료합니다.

이 버전은 검색 루트 안에서 수집한 정의만 사용합니다. 외부 경로, 상대 경로, 알 수 없는
`${...}` 속성은 임의 해석하지 않고 `activation-issues.csv`에 남깁니다.
필요하면 별도의 설정 복사본에 절대 경로를 지정하고 위 VM 옵션으로 분석하세요.
서버·Ant 설정의 속성 확장 전체, 사용자 정의 빌드 로직, 실제 테넌트/서버 로딩 결과는 재현하지 않습니다.
파일이 없거나 XML이 잘못되면 전체 Extension 의존관계 CSV는 계속 생성하고, 활성 판정은 UNKNOWN입니다.
`summary.csv`의 `selection_complete`는 지원하는 정적 규칙의 해석 완전성입니다.
`scan_complete`와 구분되며 런타임 검증을 뜻하지 않습니다.
`extensions.csv`에도 `selection_status` 열을 추가했습니다. 기존 6개 파일은 전체 발견 범위를 유지합니다.

설정 규칙 참고:
[SAP Configuration Options in localextensions.xml](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html),
[SAP Extension Dependencies](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/20125f0eca6340dba918bda360e3cdfa/8bbf3a9d86691014aa4189bf3ac0eb88.html).

검증용 소스는 `test/com/sapcommerce/analyzer/ActiveExtensionAnalyzerTest.java`에 있습니다.
외부 라이브러리 없이 Java 21에서 src와 함께 별도의 작업 폴더로 컴파일해 실행할 수 있습니다.
Eclipse의 기본 src에는 테스트를 포함하지 않으므로 운영 실행에는 영향을 주지 않습니다.
