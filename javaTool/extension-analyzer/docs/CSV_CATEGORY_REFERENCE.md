# 결과 CSV의 카테고리 값 정리 (extension-analyzer)

`ExtensionAnalyzer` 실행 결과 폴더(`reports/<날짜_시각>/`)의 CSV에서
**정해진 값 중 하나가 들어가는 열(카테고리 열)**과 그 값의 뜻을 정리한 문서입니다.
spring-bean-analyzer-from-xml에 있던 extension CSV 설명을 이 도구로 옮겼습니다.

- 값은 소스 코드(`src/com/sapcommerce/extension/*.java`)에서 실제로 기록하는 문자열을 기준으로 모았습니다.
- 경로, 이름, 개수, 원문 등 **자유 값 열은 제외**했습니다.
- 모든 판정은 정적 분석 결과입니다. 실행 중인 서버의 상태를 확인한 값은 없습니다.

### `summary.csv`

`metric`, `value` 두 열로 된 요약입니다. 아래 지표는 결과를 믿을 수 있는지 판단하는 값입니다.

| metric | 값 | 뜻 |
| --- | --- | --- |
| `scan_complete` | `true`/`false` | `extensioninfo.xml` 파싱 실패와 폴더 접근 실패가 모두 0인지 |
| `selection_complete` | `true`/`false` | `localextensions.xml` 해석에 판정하지 못한 문제가 없는지(`activation-issues.csv`가 비어 있는지) |
| `selection_basis` | `STATIC_CONFIG_INFERENCE; runtime not inspected` | 설정 기반 추정임을 표시 |
| `load_order_basis` | 고정 문구 | 로드 순서에 쓴 근거(requires-extension, bin/platform/ext, load-prior-to). 그 밖의 쌍은 미확정 |

### `extensions.csv` (선택된 extension만)

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `selection_status` | `extension-activation.csv`와 같음 | 아래 참고 |
| `core_module`, `web_module` | `true`/`false` | `<coremodule>`, `<webmodule>` 선언 여부 |
| `platform_extension` | `true`/`false` | `bin/platform/ext` 아래에 있는지(다른 extension보다 먼저 로드됨) |
| `dependency_cycle_group` | `0` | `requires-extension` 순환 그룹에 속하지 않음 |
| | `1` 이상 | `cycles.csv`의 순환 그룹 번호 |
| `load_order_cycle` | `true`/`false` | 선택된 extension 사이의 requires/load-prior-to 관계가 순환함. 순환 안의 extension끼리는 순서 미확정 |

`load_prior_to`는 `<meta key="load-prior-to" value="…"/>`에 적힌 이름 목록(`;` 구분)입니다.

### `dependencies.csv`, `reverse-dependencies.csv`

발견한 모든 정의(선택 여부와 무관)의 직접 `requires-extension` 관계입니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `status` | `FOUND` | `requires-extension` 대상의 `extensioninfo.xml`을 스캔에서 찾음 |
| | `NOT_FOUND` | 대상을 찾지 못함(`missing-dependencies.csv`에도 기록) |
| `source_selection_status`, `dependent_selection_status` | `extension-activation.csv`와 같음 | 의존을 선언한 쪽의 선택 상태 |
| `cycle_group` | `0` / `1` 이상 | 같은 순환 그룹 안의 간선이면 그 번호 |

### `missing-dependencies.csv`, `cycles.csv`

카테고리 열은 선택 상태뿐입니다. `cycles.csv`의 `cycle_group`은 순환 그룹 번호(1부터)입니다.
순환 그룹은 강하게 연결된 컴포넌트(SCC)이며, 이름순 나열은 순환 경로의 순서가 아닙니다.

### `extension-load-order.csv`

선택된 extension 사이에서 **로드 순서가 보장된 쌍**만 기록합니다. 이 파일에 없는 쌍은 순서 미확정입니다.
(이름순·폴더순으로 임의로 정하지 않습니다.)

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `basis` | `REQUIRES` | `loads_before`가 `extension`을 `requires-extension`으로 직접 요구함 |
| | `PLATFORM` | `extension`은 `bin/platform/ext` 아래, `loads_before`는 아님 |
| | `LOAD_PRIOR_TO` | `extension`의 `<meta key="load-prior-to">`에 `loads_before`가 있음 |
| | `TRANSITIVE` | 직접 근거는 없고 다른 extension을 거쳐서 보장됨 |
| | 여러 값 `;` 구분 | 직접 근거가 둘 이상(예: `PLATFORM;REQUIRES`) |

순환(`load_order_cycle=true`) 안의 extension끼리는 행이 없습니다.

### `extension-activation.csv`

`localextensions.xml` 해석 결과입니다. 판정은 **설치 루트 전체 스캔 → `localextensions.xml`로 선택 판정**의 순서로 진행합니다.
먼저 설치 루트 아래의 모든 `extensioninfo.xml`을 찾고, 찾은 extension마다 `localextensions.xml` 규칙으로 선택 여부를 정합니다.

- 행은 **찾은 `extensioninfo.xml` 하나당**(같은 이름이면 사용하는 정의 하나당) 한 행입니다. 행 수는 로드되는 extension 수가 아닙니다.
- `NOT_SELECTED`는 "폴더에는 있지만 선택되지 않음"입니다. "선언했는데 없음"이 아닙니다.
- `localextensions.xml`에 선언했지만 폴더를 찾지 못한 extension은 이 파일에 **행이 없고**, `activation-issues.csv`에만 기록됩니다.

자세한 흐름은 README의 「판정 방법」을 참고하세요.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `selection_status` | `EXPLICIT` | `<extension name="…">` 또는 `<extension dir="…">`로 지정됨 → **로드됨** |
| | `AUTOLOAD` | `<path autoload="true">` 범위에서 발견됨 → **로드됨** |
| | `PLATFORM` | `<extensions autoload>`가 `true`(생략 시 기본값)이고 `bin/platform/ext` 아래에 있음 → **로드됨** |
| | `DEPENDENCY` | 선택된 extension의 `requires-extension`을 따라 포함됨 → **로드됨** |
| | `NOT_SELECTED` | 해석할 수 있는 설정 안에서 선택되지 않음 → 로드되지 않음 |
| | `UNKNOWN` | 해석 문제가 있어 선택 여부를 판단할 수 없음(`activation-issues.csv` 참고) |
| `reason` | `Declared in localextensions.xml` | `EXPLICIT`의 사유 |
| | `path autoload=true` | `AUTOLOAD`의 사유 |
| | `Default platform/ext autoload` | `PLATFORM`의 사유 |
| | `Required by selected extension` | `DEPENDENCY`의 사유. `selected_by`에 요구한 extension 이름 |
| | `Not selected by modeled configuration rules` | `NOT_SELECTED`의 사유 |
| | `Selection is incomplete; see activation-issues.csv` | `UNKNOWN`의 사유 |
| `basis` | `STATIC_CONFIG_INFERENCE` | 공통 값 |

한 extension이 여러 사유에 해당하면 먼저 확인된 사유 하나만 기록합니다(직접 지정이 자동 포함보다 우선).

### `activation-issues.csv`

`reference`, `message` 열로 된 문제 목록이며 `message`는 자유 텍스트입니다. 주요 메시지 유형은 다음과 같습니다.

| `message`의 시작 문구 | 뜻 |
| --- | --- |
| `Cannot evaluate localextensions.xml` | 설정 파일이 없거나 XML 오류 등으로 읽지 못함 |
| `Extension scan is incomplete` | `extensioninfo.xml` 스캔이 불완전함 |
| `Unsupported configuration element` / `Unsupported attribute` | 지원하지 않는 요소·속성 |
| `Empty path or unsupported property placeholder` | 알 수 없는 `${…}` 변수 또는 빈 경로 |
| `Relative directory requires confirmation` | 상대 경로(기준을 추측하지 않음) |
| `Configured directory is outside scan root` / `does not exist` | 설치 루트 밖이거나 존재하지 않는 폴더 |
| `Name not found within configured lookup paths` | `<extension name>`을 검색 경로에서 찾지 못함 |
| `No scanned extensioninfo.xml at configured directory` | `<extension dir>` 폴더에 `extensioninfo.xml`이 없음 |
| `Configured name does not match directory definition` | `name`과 `dir`의 extension 이름이 다름 |
| `Required definition not found in scan` | `requires-extension` 대상이 스캔에 없음 |
| `Required definition is outside configured lookup paths` | `requires-extension` 대상이 검색 경로 밖에 있음 |
| `Duplicate extension name under the same lookup path` | 같은 `<path>` 아래에 같은 이름의 extension이 둘 이상 |
| `Duplicate <extension> declaration` | 같은 extension을 `<extension>`으로 두 번 이상 선언 |
| `Negative lookup depth` 등 | `depth` 값 오류 |

### `extension-duplicates.csv`

같은 이름의 `extensioninfo.xml`이 여러 개일 때, 사용하지 않은 정의를 기록합니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `reason` | `SHADOWED_BY_EARLIER_PATH` | 다른 `<path>` 아래에도 있었지만 먼저 적힌 `<path>`의 정의를 사용함 |
| | `OUT_OF_LOOKUP` | 어느 검색 경로에도 속하지 않는 복사본이라 무시함 |
| | `SAME_PATH_AMBIGUOUS` | 같은 `<path>` 아래에 둘 이상 있어 하나로 정할 수 없음(이슈로도 기록) |
| | `OVERRIDDEN_BY_DIR_DECLARATION` | `<extension dir>`로 다른 폴더를 지정해서 이 정의는 쓰지 않음 |

