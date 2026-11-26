# extension-analyzer

지정한 `HYBRIS_HOME`의 SAP Commerce extension을 분석해서 CSV로 저장하는 Java 21 도구입니다.
**extension에 관한 분석은 모두 이 도구가 합니다.**

- 활성화(선택)된 extension 판정 (`localextensions.xml` 기반)
- `requires-extension` 의존관계·역의존·누락·순환 그룹 (발견한 모든 정의 대상)
- 선택된 extension 사이의 **보장된 로드 순서** (`requires-extension`, `bin/platform/ext`, `load-prior-to`)

예전 이름은 `activated-extension-extractor`입니다(활성 판정만 하던 도구).
`spring-bean-analyzer-from-xml`에 있던 의존관계 분석과 로드 순서 계산을 이 도구로 옮겨 합쳤습니다.
[`spring-bean-analyzer-from-xml`](../spring-bean-analyzer-from-xml/README.md)은 extension을 직접 해석하지 않고 이 도구의 가장 최근 결과(`reports` 폴더)만 읽습니다.
Java 표준 API만 쓰기 때문에 Maven, Gradle, 외부 jar가 필요 없습니다. SAP Commerce 폴더는 읽기만 하고 수정하지 않습니다.

> "활성화"와 "로드 순서"는 **설정 파일을 읽고 정적 규칙으로 추정한 결과**입니다. 실행 중인 서버나 빌드 결과를 확인한 것이 아닙니다.
> 실제로 로드되는 목록과 순서는 `ant` 빌드 로그나 HAC에서 최종 확인하세요.

## 준비물

- JDK 21 이상 (`java`, `javac`가 PATH에 있어야 합니다)
- 분석할 SAP Commerce 설치 폴더 (`HYBRIS_HOME`, 예: `C:\hybris`)와 그 안의 `config/localextensions.xml`

## 실행

### 명령 프롬프트

`run.cmd`가 빌드(`build.cmd`)와 실행을 함께 합니다.

```powershell
.\run.cmd C:\hybris
```

- 인자를 생략하면 `C:\hybris`를 분석합니다.
- 두 번째 인자로 extension 이름을 주면 그 extension의 `requires-extension` 트리를 콘솔에 추가로 출력합니다(예: `.\run.cmd C:\hybris mmmcore`). 그 이름이 없으면 종료 코드는 1입니다.
- `--help`를 주면 사용법을 출력합니다.
- 결과는 실행한 위치의 `reports\yyyyMMdd_HHmmss_고유값\` 폴더에 저장됩니다. 실행할 때마다 새 폴더를 만들기 때문에 이전 결과를 덮어쓰지 않습니다.

JVM 옵션은 환경변수 `EXTENSION_ANALYZER_OPTS`로 넘깁니다.

```powershell
$env:EXTENSION_ANALYZER_OPTS = '-Dextractor.output=C:\extension-results -Dextractor.localextensions=C:\config-copy\localextensions.xml'; .\run.cmd C:\hybris
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| 첫 번째 인자 | `C:\hybris` | 분석할 `HYBRIS_HOME` |
| 두 번째 인자 | 없음 | 의존 트리를 출력할 extension 이름 |
| `-Dextractor.localextensions=<파일>` | `<HYBRIS_HOME>/config/localextensions.xml` | 다른 설정 파일(복사본 등)로 판정할 때 |
| `-Dextractor.output=<폴더>` | `reports` | 결과 폴더의 상위 폴더 |

`-D` 옵션 이름(`extractor.*`)은 예전 이름과의 호환을 위해 그대로 두었습니다.

### Eclipse

1. **File → Import → General → Existing Projects into Workspace**로 이 폴더를 가져옵니다.
2. JavaSE-21 JRE가 연결되어 있는지 확인합니다.
3. 실행 클래스 `src/execute/Execute.java`(`execute.Execute`)를 **Run As → Java Application**으로 실행합니다.
   - 경로는 **Program arguments**에, `-D` 옵션은 **VM arguments**에 넣습니다.

예전 이름으로 가져온 프로젝트가 Eclipse에 남아 있으면 지우고 다시 가져오세요.

### 종료 코드

| 코드 | 의미 |
|---|---|
| `0` | 스캔과 선택 판정이 모두 완료됨 |
| `1` | `HYBRIS_HOME`이 없음, 스캔 실패(파싱/접근)가 있음, 선택 판정에 문제가 있음(`activation-issues.csv`에 행이 있음), 또는 트리를 요청한 extension이 없음. 이때도 CSV는 저장합니다. |

의존 누락과 순환은 그 자체로는 종료 코드를 바꾸지 않습니다.

## 출력 CSV

모든 CSV는 UTF-8 BOM, CRLF 줄바꿈을 씁니다. 모든 필드를 큰따옴표로 감싸고, 필드 안의 큰따옴표는 두 번 써서 표시합니다. 행이 없어도 헤더는 항상 씁니다.
전체 13개입니다. 카테고리 값의 뜻은 [docs/CSV_CATEGORY_REFERENCE.md](docs/CSV_CATEGORY_REFERENCE.md)에 있습니다.

### 활성 판정

| 파일 | 행 단위 | 내용 |
|---|---|---|
| `extensions.csv` | **선택된 extension만** 하나당 한 행 | 활성화된 extension 목록입니다. 선택 상태와 근거, 위치, core/web 모듈 여부, 의존 목록, load-prior-to, 순환 여부를 담습니다. |
| `extension-activation.csv` | 발견한 모든 extension(같은 이름이면 사용하는 정의 하나) | 선택되지 않은 것(`NOT_SELECTED`)과 판단할 수 없는 것(`UNKNOWN`)도 포함한 전체 판정 결과입니다. |
| `activation-issues.csv` | 문제 하나당 한 행 | 설정 해석 문제입니다. 선언했는데 찾지 못한 이름, 경로 오류, 지원하지 않는 속성, 의존 대상 누락, 같은 경로 안의 중복 등이 여기에 나옵니다. |
| `extension-duplicates.csv` | 사용하지 않은 중복 정의 하나당 한 행 | 같은 이름의 `extensioninfo.xml`이 여러 개일 때, 사용하지 않은 쪽과 그 이유입니다. |
| `summary.csv` | 지표 하나당 한 행 | 찾은/파싱한 XML 수, 스캔 완전성(`scan_complete`), 상태별 개수, 선택 판정 완전성(`selection_complete`), 의존 간선·누락·순환 수, 순서 쌍 수 |
| `scan-failures.csv` | 실패 하나당 한 행 | 파싱하지 못했거나 접근하지 못한 `extensioninfo.xml`/폴더(`PARSE_FAILED`, `ACCESS_FAILED`, `INCOMPLETE_DIRECTORY`). 이런 extension은 목록에 아예 나오지 않습니다. |
| `lookup-paths.csv` | 검색 경로 하나당 한 행 | 판정에 쓴 extension 검색 경로(우선순위 순서) |
| `activation-notes.csv` | 항목 하나당 한 행 | `<webapp>` 선언처럼, 기록은 했지만 선택 판정에는 영향을 주지 않는 항목 |

`extensions.csv`는 [`staticAnalyzer`](../staticAnalyzer/README.md)의 `--extensions-csv` 입력으로 쓸 수 있습니다. 이렇게 하면 호출 트리 분석을 활성 extension으로 좁힙니다. staticAnalyzer는 같은 폴더의 `summary.csv`도 함께 읽기 때문에 두 파일을 같은 폴더에 두세요. `--extensions-csv`에 이 도구의 `reports` 폴더를 지정하면, staticAnalyzer가 가장 최근 결과 폴더를 자동으로 고릅니다.

`extensions.csv`의 열은 다음과 같습니다.

| 열 | 내용 |
|---|---|
| `extension` | extension 이름 |
| `selection_status` | `EXPLICIT` / `AUTOLOAD` / `PLATFORM` / `DEPENDENCY` (아래 「선택 상태」 참고) |
| `reason`, `selected_by` | 선택된 근거와 근거가 된 대상. `DEPENDENCY`이면 그 extension을 요구한 extension 이름이 들어갑니다. |
| `relative_dir` | `HYBRIS_HOME` 기준 상대 폴더 (예: `bin/custom/shop`) |
| `xml_path` | `extensioninfo.xml` 절대 경로 |
| `core_module` | `<coremodule>` 선언 여부 |
| `web_module`, `webroot` | `<webmodule>` 선언 여부와 `webroot` 값 |
| `direct_dependency_count`, `dependencies` | `requires-extension` 수와 목록 (`;`로 구분, 선언 순서) |
| `selected_dependent_count` | 이 extension을 직접 요구하는 **선택된** extension 수 |
| `missing_dependency_count` | 요구하지만 스캔에서 찾지 못한 extension 수 |
| `load_prior_to` | `<meta key="load-prior-to" value="…"/>`에 적힌 이름 (`;`로 구분) |
| `platform_extension` | `bin/platform/ext` 아래에 있는지 |
| `dependency_cycle_group` | `requires-extension` 순환 그룹 번호(`cycles.csv`). `0`은 순환 없음 |
| `load_order_cycle` | 선택된 extension 사이의 requires/load-prior-to 관계가 순환하는지. `true`이면 순환 안의 extension끼리는 순서 미확정 |

새 열은 끝에 붙였기 때문에, 열 이름으로 읽는 staticAnalyzer는 그대로 동작합니다.

### 의존관계 (spring-bean-analyzer-from-xml에서 옮김)

발견한 **모든 정의**(선택 여부와 무관, 같은 이름이면 사용하는 정의 하나)를 대상으로 합니다. 직접 관계만 저장하고 간접 경로를 펼치지 않습니다.

| 파일 | 내용 |
|---|---|
| `dependencies.csv` | 출발 extension(과 그 선택 상태) → 직접 의존 extension, 대상 발견 여부(`FOUND`/`NOT_FOUND`), 순환 그룹 |
| `reverse-dependencies.csv` | 의존 대상 → 이를 요구하는 extension. 누락 대상도 포함 |
| `missing-dependencies.csv` | 찾지 못한 대상과 이를 요구한 extension·선택 상태·XML 경로. 선택된 extension의 행은 빌드 실패 원인 후보입니다. |
| `cycles.csv` | 순환 그룹 번호별 소속 extension. 순환 그룹은 강하게 연결된 컴포넌트(SCC)이고, 이름순 나열은 순환 경로의 순서가 아닙니다. 실제 간선은 `dependencies.csv`에서 확인합니다. |

`missing-dependencies.csv`는 예전에는 선택된 extension의 누락만 담았습니다. 지금은 선택되지 않은 정의의 누락도 담고 `source_selection_status`로 구분합니다.

### 로드 순서

| 파일 | 내용 |
|---|---|
| `extension-load-order.csv` | 선택된 extension 사이에서 **로드 순서가 보장된 쌍**(`extension`이 `loads_before`보다 먼저)과 근거(`basis`) |

## 판정 방법

1. **설치 폴더 전체 스캔**: `HYBRIS_HOME` 아래의 모든 `extensioninfo.xml`을 찾습니다. `localextensions.xml`에 적혀 있는지와 상관없이 전부 후보가 됩니다.
   - `node_modules`, `.git`, `temp` 폴더는 건너뜁니다. 심볼릭 링크는 따라가지 않습니다.
2. **`localextensions.xml`로 선택 판정**: 후보마다 `<extension>` 선언, `<path>`의 검색 범위와 `autoload`, `<extensions autoload>`, `requires-extension` 연쇄를 적용해서 선택 여부와 근거를 정합니다.
3. **의존관계 분석**: 사용하는 정의 전체의 `requires-extension` 간선, 누락, 순환 그룹을 구합니다.
4. **로드 순서**: 선택된 extension끼리 보장된 순서만 구합니다(아래 「로드 순서」).

### 선택 상태

| 상태 | 의미 | `extensions.csv`에 포함 |
|---|---|---|
| `EXPLICIT` | `<extension name="...">` 또는 `<extension dir="...">`로 지정됨 | O |
| `AUTOLOAD` | `<path autoload="true">` 범위 안에서 발견됨 | O |
| `PLATFORM` | `<extensions autoload>`가 `true`(기본값)여서 `bin/platform/ext` 아래가 자동 포함됨 | O |
| `DEPENDENCY` | 선택된 extension의 `requires-extension`을 따라가서 포함됨 | O |
| `NOT_SELECTED` | 해석할 수 있는 규칙 중 어디에도 해당하지 않음 | X |
| `UNKNOWN` | 설정이나 스캔에 문제가 있어 판단할 수 없음 | X |

규칙의 세부 내용:

- `<path>`의 `depth` 기본값은 10입니다. 지정한 폴더 자체가 깊이 0, 바로 아래 extension 폴더가 깊이 1입니다.
- `<extensions autoload="false">`이면 `bin/platform/ext`를 자동으로 포함하지 않고 검색 경로로도 쓰지 않습니다. 이때 플랫폼 extension은 선언된 `<path>` 아래에서 찾아야 합니다.
- `${HYBRIS_BIN_DIR}`, `${HYBRIS_CONFIG_DIR}`, `${HYBRIS_PLATFORM_DIR}`는 `HYBRIS_HOME` 기준으로 바꿔서 해석합니다. 그 밖의 `${...}`, 상대 경로, `HYBRIS_HOME` 밖의 경로는 추측하지 않고 `activation-issues.csv`에 남깁니다.
- 직접 지정(`EXPLICIT`)이 자동 로딩보다 우선합니다. 여러 경로로 선택되면 처음 정해진 근거 하나만 기록합니다.

### 같은 이름의 extension이 여러 개일 때

| 경우 | 처리 | `extension-duplicates.csv`의 `reason` |
|---|---|---|
| 서로 다른 `<path>` 아래에 있음 | 먼저 적힌 `<path>` 아래의 정의를 사용 | `SHADOWED_BY_EARLIER_PATH` |
| 어느 검색 경로에도 속하지 않음 (백업 복사본 등) | 무시 | `OUT_OF_LOOKUP` |
| `<extension dir>`로 위치를 지정함 | 지정한 폴더의 정의를 사용 | `OVERRIDDEN_BY_DIR_DECLARATION` |
| 같은 `<path>` 아래에 둘 이상 있음 | 경로 순서상 첫 번째를 쓰고 `activation-issues.csv`에 기록 | `SAME_PATH_AMBIGUOUS` |

같은 extension을 `<extension>`으로 두 번 이상 선언하면 `activation-issues.csv`에 기록합니다. SAP 문서에 동작이 정의되어 있지 않아서 자동으로 판정하지 않습니다.

### 로드 순서

SAP Help 「Extension Dependencies」가 보장하는 관계만 씁니다.

| 관계 | 순서 | `basis` |
|---|---|---|
| B가 `requires-extension` A | A가 먼저 | `REQUIRES` |
| A는 `bin/platform/ext` 아래, B는 아님 | A가 먼저 (플랫폼 extension에 대한 암묵적 의존) | `PLATFORM` |
| A의 `<meta key="load-prior-to" value="B"/>` | A가 먼저 | `LOAD_PRIOR_TO` |
| 위 관계를 여러 번 거쳐서 이어짐 | 앞쪽이 먼저 | `TRANSITIVE` |

- 그 밖의 쌍은 **순서 미확정**이며 `extension-load-order.csv`에 행이 없습니다. 이름순이나 폴더순으로 임의로 정하지 않습니다.
- requires/load-prior-to가 순환하면 순환 안의 extension끼리는 미확정이고, `extensions.csv`의 `load_order_cycle`이 `true`입니다.
- 선택되지 않은 extension은 순서 계산에 넣지 않습니다.
- `load-prior-to`에 적힌 이름이 선택되지 않았거나 없으면 그 관계는 무시합니다.

spring-bean-analyzer-from-xml은 이 표를 그대로 읽어 같은 이름 빈의 최종 승자를 정할 때 씁니다.

근거 문서: [Configuration Options in localextensions.xml](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html), [Extension Dependencies](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/20125f0eca6340dba918bda360e3cdfa/8bbf3a9d86691014aa4189bf3ac0eb88.html)

## 한계

- 실행 중인 서버, 빌드 결과, 테넌트 설정은 확인하지 않습니다. Ant/서버의 속성 확장 전체와 사용자 정의 빌드 로직은 재현하지 않습니다.
- `extensioninfo.xml`에 DOCTYPE이 있으면 보안상 파싱을 거부합니다. 이 파일은 `scan-failures.csv`에 `PARSE_FAILED`로 나옵니다.
- 같은 `<path>` 안에서 어느 정의가 우선하는지는 SAP 문서에 없습니다. 빌드 로그의 `path:`로 확인하세요.
- 스캔에 실패가 있으면(`scan_complete=false`) 선택되지 않은 나머지 extension은 `UNKNOWN`으로 표시합니다.
- 로드 순서는 보장된 관계만 담습니다. 실제 플랫폼이 미확정 쌍을 어떤 순서로 로드하는지는 재현하지 않습니다.

## 테스트

```powershell
.\test.cmd
```

`src`와 `test`를 `test-bin`에 컴파일하고 `ExtensionAnalyzerTest`를 실행합니다. JUnit은 필요 없습니다.
- 선택 판정 규칙 전체(명시 지정, 경로 자동 로딩, 플랫폼 기본 포함, 의존 연쇄, 중복 정의, 오류 처리)
- 검색 경로 순서, core/web 모듈 정보, `extensions.csv`에 선택된 extension만 들어가는지
- 로드 순서: 플랫폼·requires(전이)·load-prior-to, 관계없는 쌍의 미확정, 순환, `basis` 값
- 13개 CSV 생성, 의존관계·역의존·순서 CSV 내용, 파싱 실패와 의존 누락 기록, 종료 코드

## 소스 구조

```text
src/execute/
└── Execute.java                      실행 클래스: 인자 처리, 사용법 출력
src/com/sapcommerce/extension/
├── ExtensionAnalyzer.java            분석 실행: 스캔, 콘솔 요약, 종료 코드
├── ExtensionInfoParser.java          extensioninfo.xml 파서 (외부 엔티티/DTD 차단, load-prior-to 포함)
├── ExtensionInfo.java                extension 정의 (이름, 경로, 의존, load-prior-to, core/web 모듈)
├── ActiveExtensionAnalyzer.java      localextensions.xml 기반 선택 판정, 중복 처리, 검색 경로
├── ExtensionDependencyAnalyzer.java  requires-extension 의존관계, 순환 그룹, 의존 트리 출력
├── ExtensionOrder.java               선택된 extension 사이의 보장된 로드 순서
└── ReportWriter.java                 CSV 13개 작성
test/com/sapcommerce/extension/
└── ExtensionAnalyzerTest.java
```

`ExtensionDependencyAnalyzer`와 `ExtensionOrder`는 spring-bean-analyzer-from-xml에서 옮겨 왔습니다.
두 도구에 따로 있던 `ExtensionInfo`/`ExtensionInfoParser`는 이 도구의 것 하나로 합쳤습니다(core/web 모듈과 load-prior-to를 모두 읽음).
