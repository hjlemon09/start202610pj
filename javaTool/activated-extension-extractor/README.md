# activated-extension-extractor

지정한 `HYBRIS_HOME`에서 **활성화(선택)된 SAP Commerce extension만 추출**해서 CSV로 저장하는 Java 21 도구입니다.

`sap-commerce-analyzer`에서 extension 검출과 선택 판정 부분만 떼어 낸 독립 프로젝트입니다. Spring XML, Bean, 의존관계 그래프 분석은 들어 있지 않습니다.
Java 표준 API만 쓰기 때문에 Maven, Gradle, 외부 jar가 필요 없습니다. SAP Commerce 폴더는 읽기만 하고 수정하지 않습니다.

> "활성화"는 **설정 파일을 읽고 정적 규칙으로 추정한 결과**입니다. 실행 중인 서버나 빌드 결과를 확인한 것이 아닙니다.
> 실제로 로드되는 목록은 `ant` 빌드 로그나 HAC에서 최종 확인하세요.

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
- `--help`를 주면 사용법을 출력합니다.
- 결과는 실행한 위치의 `reports\yyyyMMdd_HHmmss_고유값\` 폴더에 저장됩니다. 실행할 때마다 새 폴더를 만들기 때문에 이전 결과를 덮어쓰지 않습니다.

JVM 옵션은 환경변수 `EXTRACTOR_OPTS`로 넘깁니다.

```powershell
$env:EXTRACTOR_OPTS = '-Dextractor.output=C:\extractor-results -Dextractor.localextensions=C:\config-copy\localextensions.xml'; .\run.cmd C:\hybris
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| 첫 번째 인자 | `C:\hybris` | 분석할 `HYBRIS_HOME` |
| `-Dextractor.localextensions=<파일>` | `<HYBRIS_HOME>/config/localextensions.xml` | 다른 설정 파일(복사본 등)로 판정할 때 |
| `-Dextractor.output=<폴더>` | `reports` | 결과 폴더의 상위 폴더 |

### Eclipse

1. **File → Import → General → Existing Projects into Workspace**로 이 폴더를 가져옵니다.
2. JavaSE-21 JRE가 연결되어 있는지 확인합니다.
3. `src/com/sapcommerce/extractor/ActivatedExtensionExtractor.java`를 **Run As → Java Application**으로 실행합니다.
   - 경로는 **Program arguments**에, `-D` 옵션은 **VM arguments**에 넣습니다.

### 종료 코드

| 코드 | 의미 |
|---|---|
| `0` | 스캔과 선택 판정이 모두 완료됨 |
| `1` | `HYBRIS_HOME`이 없음, 스캔 실패(파싱/접근)가 있음, 또는 선택 판정에 문제가 있음(`activation-issues.csv`에 행이 있음). 이때도 CSV는 저장합니다. |

## 출력 CSV

모든 CSV는 UTF-8 BOM, CRLF 줄바꿈을 씁니다. 모든 필드를 큰따옴표로 감싸고, 필드 안의 큰따옴표는 두 번 써서 표시합니다. 행이 없어도 헤더는 항상 씁니다.

### 요청한 4개 파일

| 파일 | 행 단위 | 내용 |
|---|---|---|
| `extensions.csv` | **선택된 extension만** 하나당 한 행 | 활성화된 extension 목록입니다. 선택 상태와 근거, 위치, core/web 모듈 여부, 의존 목록을 담습니다. |
| `extension-activation.csv` | 발견한 모든 extension(같은 이름이면 사용하는 정의 하나) | 선택되지 않은 것(`NOT_SELECTED`)과 판단할 수 없는 것(`UNKNOWN`)도 포함한 전체 판정 결과입니다. |
| `activation-issues.csv` | 문제 하나당 한 행 | 설정 해석 문제입니다. 선언했는데 찾지 못한 이름, 경로 오류, 지원하지 않는 속성, 의존 대상 누락, 같은 경로 안의 중복 등이 여기에 나옵니다. |
| `extension-duplicates.csv` | 사용하지 않은 중복 정의 하나당 한 행 | 같은 이름의 `extensioninfo.xml`이 여러 개일 때, 사용하지 않은 쪽과 그 이유입니다. |

`extensions.csv`는 [`staticAnalyzer`](../staticAnalyzer/README.md)의 `--extensions-csv` 입력으로 쓸 수 있습니다. 이렇게 하면 호출 트리 분석을 활성 extension으로 좁힙니다. staticAnalyzer는 같은 폴더의 `summary.csv`도 함께 읽기 때문에 두 파일을 같은 폴더에 두세요.

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

### 추가한 5개 파일

extension 검출과 관련해서 기존 도구가 콘솔에만 출력하거나 따로 확인할 수 없던 정보를 파일로 남깁니다.

| 파일 | 추가한 이유와 내용 |
|---|---|
| `summary.csv` | 결과를 믿어도 되는지 판단하는 기준입니다. 찾은/파싱한 XML 수, 스캔 완전성(`scan_complete`), 상태별 개수, 선택 판정 완전성(`selection_complete`)을 담습니다. |
| `scan-failures.csv` | **파싱하지 못했거나 접근하지 못한 `extensioninfo.xml`/폴더**의 목록입니다(`PARSE_FAILED`, `ACCESS_FAILED`, `INCOMPLETE_DIRECTORY`). 이런 extension은 목록에 아예 나오지 않기 때문에 따로 확인해야 합니다. 기존 도구는 이 정보를 콘솔에만 출력했습니다. |
| `missing-dependencies.csv` | **선택된 extension**이 `requires-extension`으로 요구하지만 설치 폴더에서 찾지 못한 대상입니다. 빌드가 실패할 원인 후보입니다. |
| `lookup-paths.csv` | 판정에 쓴 extension 검색 경로를 우선순위 순서로 나열합니다(`<path>`와, `autoload`가 켜져 있을 때의 `bin/platform/ext`). 같은 이름의 extension이 어느 쪽에서 선택됐는지(`extension-duplicates.csv`)는 이 순서로 정해집니다. |
| `activation-notes.csv` | `<webapp>` 선언처럼, 기록은 했지만 선택 판정에는 영향을 주지 않는 항목입니다. 기존 도구는 콘솔에만 출력했습니다. |

## 판정 방법

1. **설치 폴더 전체 스캔**: `HYBRIS_HOME` 아래의 모든 `extensioninfo.xml`을 찾습니다. `localextensions.xml`에 적혀 있는지와 상관없이 전부 후보가 됩니다.
   - `node_modules`, `.git`, `temp` 폴더는 건너뜁니다. 심볼릭 링크는 따라가지 않습니다.
2. **`localextensions.xml`로 선택 판정**: 후보마다 `<extension>` 선언, `<path>`의 검색 범위와 `autoload`, `<extensions autoload>`, `requires-extension` 연쇄를 적용해서 선택 여부와 근거를 정합니다.

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

근거 문서: [Configuration Options in localextensions.xml](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html), [Extension Dependencies](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/20125f0eca6340dba918bda360e3cdfa/8bbf3a9d86691014aa4189bf3ac0eb88.html)

## 한계

- 실행 중인 서버, 빌드 결과, 테넌트 설정은 확인하지 않습니다. Ant/서버의 속성 확장 전체와 사용자 정의 빌드 로직은 재현하지 않습니다.
- `extensioninfo.xml`에 DOCTYPE이 있으면 보안상 파싱을 거부합니다. 이 파일은 `scan-failures.csv`에 `PARSE_FAILED`로 나옵니다.
- 같은 `<path>` 안에서 어느 정의가 우선하는지는 SAP 문서에 없습니다. 빌드 로그의 `path:`로 확인하세요.
- 스캔에 실패가 있으면(`scan_complete=false`) 선택되지 않은 나머지 extension은 `UNKNOWN`으로 표시합니다.

## 테스트

```powershell
.\test.cmd
```

`src`와 `test`를 `test-bin`에 컴파일하고 `ActivatedExtensionExtractorTest`를 실행합니다. JUnit은 필요 없습니다.
`sap-commerce-analyzer`의 선택 판정 테스트를 그대로 옮겨 왔고, 아래 항목의 테스트를 추가했습니다.
- 검색 경로 순서
- core/web 모듈 정보
- `extensions.csv`에 선택된 extension만 들어가는지
- 9개 CSV 생성
- 파싱 실패와 의존 누락 기록
- 종료 코드

## 소스 구조

```text
src/com/sapcommerce/extractor/
├── ActivatedExtensionExtractor.java  실행 진입점: 스캔, 콘솔 요약, 종료 코드
├── ExtensionInfoParser.java          extensioninfo.xml 파서 (외부 엔티티/DTD 차단)
├── ExtensionInfo.java                extension 정의 (이름, 경로, 의존, core/web 모듈)
├── ActiveExtensionAnalyzer.java      localextensions.xml 기반 선택 판정, 중복 처리, 검색 경로
└── ReportWriter.java                 CSV 9개 작성
test/com/sapcommerce/extractor/
└── ActivatedExtensionExtractorTest.java
```

`sap-commerce-analyzer`에서 가져와 바꾼 부분:
- `ExtensionInfoParser`: `<coremodule>`, `<webmodule webroot>`를 읽도록 확장
- `ActiveExtensionAnalyzer`: 검색 경로 목록(`LookupPath`)을 결과에 포함
- 선택 판정 로직 자체는 같습니다.
