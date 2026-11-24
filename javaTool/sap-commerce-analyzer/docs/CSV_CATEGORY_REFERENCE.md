# 결과 CSV의 카테고리 값 정리

`ExtensionScanner` 실행 결과 폴더(`reports/<날짜_시각>/`)에 생성되는 CSV 22개에서,
**정해진 값 중 하나가 들어가는 열(카테고리 열)**과 그 값의 뜻을 정리한 문서입니다.

- 값은 소스 코드(`src/com/sapcommerce/analyzer/*.java`)에서 실제로 기록하는 문자열을 기준으로 모았습니다.
- 경로, 이름, 줄 번호, 개수, 원문 등 **자유 값 열은 제외**했습니다.
- 모든 판정은 정적 분석 결과입니다. 실행 중인 서버의 상태를 확인한 값은 없습니다.

## 공통 값

여러 CSV에 반복해서 나오는 값입니다.

| 값 | 나오는 열 | 뜻 |
| --- | --- | --- |
| `NOT_EVALUATED` | `loading_status`, `runtime_classpath_status`, `runtime_loading_status`, `runtime_registration_status`, `runtime_call_status` | 실행 시점의 동작(실제 로딩·클래스패스·빈 등록·호출)은 평가하지 않았다는 표시입니다. 항상 이 값입니다. |
| `STATIC_CONFIG_INFERENCE` | `basis` | 설정 파일을 정적으로 해석한 결과라는 표시입니다. |
| `STATIC_CANDIDATE_ONLY` | `binding_certainty` | 정적으로 찾은 후보일 뿐 실제 연결이 확정된 것은 아니라는 표시입니다. |
| `true` / `false` | `is_spring_xml` 등 | 참/거짓 |

컨텍스트를 나타내는 값(`modeled_context`, `potential_entry_contexts`, `lookup_modeled_context`)은 다음 형식입니다.

| 값 | 뜻 |
| --- | --- |
| `CORE` | 테넌트 코어 컨텍스트(`<extension>.application-context` 등) |
| `GLOBAL` | 전역 컨텍스트(`<extension>.global-context`) |
| `WEB:<extension>` | 해당 extension 웹 애플리케이션의 루트 컨텍스트 |
| `SERVLET:<extension>:<servlet 이름>` | 해당 웹 애플리케이션의 서블릿 컨텍스트 |

---

## 1. extension 분석

### `summary.csv`

`metric`, `value` 두 열로 된 요약입니다. 카테고리 열은 없지만, 아래 지표는 결과를 믿을 수 있는지 판단하는 값입니다.

| metric | 값 | 뜻 |
| --- | --- | --- |
| `scan_complete` | `true`/`false` | `extensioninfo.xml` 파싱 실패와 폴더 접근 실패가 모두 0인지 |
| `selection_complete`, `active_extensions_evaluated` | `true`/`false` | `localextensions.xml` 해석에 판정하지 못한 문제가 없는지(`activation-issues.csv`가 비어 있는지) |
| `selection_basis` | `STATIC_CONFIG_INFERENCE; runtime not inspected` | 설정 기반 추정임을 표시 |
| `spring_runtime_loading_evaluated` | `false` | Spring 런타임 로딩은 평가하지 않음 |
| `cycle_group_zero` | `Not in a detected cycle group` | 다른 CSV의 `cycle_group` 값 `0`의 뜻 |
| `bean_reference_status_<값>` | 개수 | `spring-bean-references.csv`의 `binding_status` 값별 행 수 |

### `extensions.csv`

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `cycle_group` | `0` | 순환 의존 그룹에 속하지 않음 |
| | `1` 이상 | `cycles.csv`의 순환 그룹 번호 |
| `selection_status` | `extension-activation.csv`와 같음 | 아래 참고 |

### `dependencies.csv`, `reverse-dependencies.csv`

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `status` | `FOUND` | `requires-extension` 대상의 `extensioninfo.xml`을 스캔에서 찾음 |
| | `NOT_FOUND` | 대상을 찾지 못함(`missing-dependencies.csv`에도 기록) |
| `cycle_group` | `0` / `1` 이상 | `extensions.csv`와 같음 |

### `missing-dependencies.csv`, `cycles.csv`

카테고리 열이 없습니다. `cycles.csv`의 `cycle_group`은 순환 그룹 번호(1부터)입니다.

### `extension-activation.csv`

`localextensions.xml` 해석 결과입니다. 분석은 **설치 루트 전체 스캔 → `localextensions.xml`로 선택 판정**의 순서로 진행합니다.
먼저 설치 루트 아래의 모든 `extensioninfo.xml`을 찾고, 찾은 extension마다 `localextensions.xml` 규칙으로 선택 여부를 정합니다.

- 행은 **찾은 `extensioninfo.xml` 하나당**(같은 이름이면 사용하는 정의 하나당) 한 행입니다. 행 수는 로드되는 extension 수가 아닙니다.
- `NOT_SELECTED`는 "폴더에는 있지만 선택되지 않음"입니다. "선언했는데 없음"이 아닙니다.
- `localextensions.xml`에 선언했지만 폴더를 찾지 못한 extension은 이 파일에 **행이 없고**, `activation-issues.csv`에만 기록됩니다.

자세한 흐름은 README 「localextensions.xml 기반 활성 대상 추정」의 「분석 순서」를 참고하세요.

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

---

## 2. Spring XML과 import 분석

### `spring-xml-files.csv`

선택된 extension의 `resources`와 `web/webroot` 아래에서 조사한 XML입니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `area` | `RESOURCES` | extension의 `resources` 폴더 |
| | `WEB` | extension의 `web/webroot` 폴더 |
| `is_spring_xml` | `true`/`false` | Spring beans 문서인지 |
| `parse_status` | `PARSED` | Spring XML로 파싱함 |
| | `PARSED_DTD_SKIPPED` | DOCTYPE이 있는 Spring XML. 외부 DTD는 읽지 않고 파싱함 |
| | `NOT_SPRING` | Spring XML이 아님 |
| | `NOT_SPRING_BEAN_GENERATION_XML` | SAP의 빈(DTO) 생성용 `*-beans.xml`(beans.xsd). Spring 빈 정의가 아님 |
| | `SKIPPED_NO_ROOT_ELEMENT` | 선언·주석만 있고 루트 요소가 없는 XML |
| | `PARSE_ERROR` | XML 파싱 실패 |
| `loading_status` | `NOT_EVALUATED` | 공통 값 |

### `spring-imports.csv`, `spring-reverse-imports.csv`

`<import resource="…">` 한 건이 대상 후보 하나당 한 행입니다. 두 파일은 정렬 방향만 다르고 열은 같습니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `resolution_status` | `RESOLVED_RELATIVE` | 출발 XML의 폴더를 기준으로 대상 파일을 찾음 |
| | `RESOLVED_RELATIVE_LEADING_SLASH` | 앞의 `/`를 빼고 출발 XML 기준 상대 경로로 찾음 |
| | `RESOLVED_JAR_RELATIVE` | 같은 JAR 안에서 출발 entry의 폴더를 기준으로 찾음 |
| | `CLASSPATH_CANDIDATE` | `classpath:` 대상 후보가 하나 |
| | `CLASSPATH_ALL_CANDIDATE` | `classpath*:` 대상 후보(여러 개일 수 있음) |
| | `AMBIGUOUS_CLASSPATH` | `classpath:` 후보가 여러 개. 클래스패스 순서를 알 수 없음 |
| | `FOUND_IN_UNSELECTED_EXTENSION` | 선택되지 않은(`NOT_SELECTED`) extension에서만 찾음 |
| | `UNRESOLVED_PROPERTY` | 빈 경로, 해석할 수 없는 `${…}`·충돌·순환, 미지원 SpEL |
| | `UNSUPPORTED_LOCATION` | file/HTTP/JAR URL, UNC 경로 등 실행 환경이 필요한 위치 |
| | `NOT_FOUND_IN_SCOPE` | 조사 범위에서 찾지 못함. 실제 부재를 단정하지 않음 |
| `target_parse_status` | `spring-xml-files.csv`의 `parse_status` 값 | 대상 XML의 파싱 결과 |
| | `SOURCE_FILE_NOT_PARSED` | 선택되지 않은 extension의 소스 파일이라 파싱하지 않음 |
| | `JAR_ENTRY_NOT_PARSED` | 선택되지 않은 extension의 JAR entry라 파싱하지 않음 |
| | (빈 값) | 대상을 찾지 못함 |
| `target_kind` | `SOURCE_FILE` | 대상이 소스 폴더의 파일 |
| | `JAR_ENTRY` | 대상이 JAR 안의 entry |
| | (빈 값) | 대상 없음(`spring-imports.csv`만) |
| `target_selection_status` | `extension-activation.csv`의 `selection_status` 값 | 대상을 소유한 extension의 선택 상태 |
| | `PLATFORM_SHARED` | 대상이 플랫폼 공용 JAR(`bin/platform/lib`, `bin/platform/bootstrap/bin`)에 있음 |
| `property_status` | `spring-property-resolutions.csv`와 같음 | `resource` 원문의 `${…}` 해석 결과 |
| `runtime_classpath_status` | `NOT_EVALUATED` | 공통 값 |

### `spring-import-issues.csv`

| `status` | 뜻 |
| --- | --- |
| `INCOMPLETE_SELECTION` | extension 선택이 불완전해서 확실히 선택된 extension만 조사함 |
| `PROPERTY_FILE_ERROR` | import 속성 해석에 쓰는 프로퍼티 파일을 읽지 못함 |
| `ACCESS_ERROR` | 폴더나 파일에 접근하지 못함 |
| `XML_INSPECTION_ERROR` | 소스 XML 검사 중 오류 |
| `SKIPPED_NO_ROOT_ELEMENT` | 루트 요소가 없는 XML을 건너뜀 |
| `JAR_INDEX_ERROR` | 선택된 extension의 JAR 목록 작성 실패 |
| `JAR_XML_INSPECTION_ERROR` | JAR 안의 XML 검사 중 오류 |
| `TARGET_NOT_PARSED_SPRING` | import 대상이 Spring XML이 아니거나 파싱되지 않음 |
| `AMBIGUOUS_CLASSPATH` | `classpath:` 후보가 여러 개 |
| `UNRESOLVED_PROPERTY` / `UNSUPPORTED_LOCATION` / `NOT_FOUND_IN_SCOPE` | import 해석 실패(`resolution_status`와 같은 뜻) |
| `FOUND_IN_UNSELECTED_EXTENSION` | 선택되지 않은 extension에만 존재(존재 여부만 기록) |
| `UNSELECTED_SOURCE_ACCESS_ERROR` | 선택되지 않은 extension 보조 검색 중 접근 실패 |
| `UNSELECTED_JAR_INDEX_ERROR` | 선택되지 않은 extension의 JAR 목록 작성 실패 |

로딩 진입점(아래 3장)을 해석하다 생긴 문제는 이 파일이 아니라 `spring-loading-issues.csv`로 옮겨 기록합니다.

### `spring-jar-files.csv`

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `search_scope` | `EXTENSION_LIBRARY` | extension 폴더 아래의 JAR(웹 라이브러리 제외) |
| | `WEB_LIBRARY` | extension의 `web/webroot` 아래 JAR. 해당 웹 컨텍스트에서만 사용 |
| | `PLATFORM_SHARED` | 플랫폼 공용 JAR(`bin/platform/lib`, `bin/platform/bootstrap/bin`) |
| `index_status` | `INDEXED` | JAR 안의 XML 목록을 작성함 |
| | `INDEX_ERROR` | JAR을 읽지 못함 |
| `selection_scope` | `SELECTED_OR_PLATFORM_SHARED` | 선택된 extension 또는 플랫폼 공용 JAR |
| | `NOT_SELECTED` | 선택되지 않은 extension의 JAR(보조 검색용) |
| `runtime_classpath_status` | `NOT_EVALUATED` | 공통 값 |

### `spring-property-resolutions.csv`

`${…}`가 들어 있는 import만 기록합니다(`LITERAL`은 기록하지 않음).

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `property_status` | `LITERAL` | `${…}` 없음(`spring-imports.csv`에만 나옴) |
| | `PROPERTY` | 프로퍼티 값으로 치환함 |
| | `DEFAULT` | 프로퍼티가 없어 `${key:default}`의 기본값을 사용함 |
| | `PROPERTY_AND_DEFAULT` | 여러 `${…}` 중 일부는 프로퍼티, 일부는 기본값으로 치환함 |
| | `UNRESOLVED` | 치환하지 못함(`detail`에 사유) |
| `basis` | `STATIC_CONFIG_INFERENCE` | 공통 값 |

### `spring-jar-xml-files.csv`

import로 도달한 JAR 안의 XML입니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `is_spring_xml` | `true`/`false` | Spring beans 문서인지 |
| `parse_status` | `PARSED`, `PARSED_DTD_SKIPPED`, `NOT_SPRING`, `NOT_SPRING_BEAN_GENERATION_XML`, `PARSE_ERROR` | `spring-xml-files.csv`와 같은 뜻 |
| `runtime_loading_status` | `NOT_EVALUATED` | 공통 값 |

---

## 3. Spring 로딩 진입점

### `spring-loading-entrypoints.csv`

각 컨텍스트가 처음 읽는 Spring XML(진입점) 후보입니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `modeled_context` | `CORE`, `GLOBAL`, `WEB:<ext>`, `SERVLET:<ext>:<name>` | 공통 값 참고 |
| `context_kind` | `CORE` / `GLOBAL` / `WEB` / `SERVLET` | 컨텍스트 종류 |
| `declared_loader` | `SAP_PROPERTY` | `<extension>.application-context` 등 프로퍼티로 선언됨 |
| | `SAP_CORE_CONVENTION` | `<extension>.application-context` 프로퍼티가 없어, `resources/<extension>-application-context.xml` 파일을 코어 진입점으로 본 경우 |
| | (클래스 이름) | `web.xml`에 적힌 리스너·서블릿 클래스 |
| `evidence_kind` | `DECLARED_PROPERTY` | 프로퍼티 값이 근거 |
| | `CONVENTION` | 기본 파일명 규칙이 근거(실행 시 사용 여부는 미확인) |
| | `DECLARED_WEB_XML` | `web.xml`의 `contextConfigLocation`이 근거(인식한 로더) |
| | `DECLARED_UNVERIFIED_LOADER` | `web.xml`에 선언됐지만 로더 구현을 확인하지 못함 |
| | `SPRING_DEFAULT_INFERRED` | 선언이 없어 Spring 기본 위치(`/WEB-INF/applicationContext.xml` 등)로 추정 |
| | `SAP_WEB_PROPERTY_INFERRED` | SAP 웹 로더가 읽는 프로퍼티에서 추정 |
| `match_status` | `spring-imports.csv`의 `resolution_status`와 같음 | 진입점 위치를 실제 XML로 찾은 결과 |
| `target_parse_status` | `spring-imports.csv`와 같음 | 진입 XML의 파싱 결과 |
| `related_entry_status` | `NOT_APPLICABLE` | 진입점을 찾았으므로 관련 정보가 필요 없음 |
| | `SAME_FILENAME_ONLY` | 진입점을 찾지 못했지만, 같은 파일명의 다른 파싱된 진입점이 있음(같은 파일이라는 뜻은 아님) |
| | `NO_PARSED_ENTRY_WITH_SAME_FILENAME` | 같은 파일명의 다른 진입점도 없음 |
| | `UNSUPPORTED_FILENAME_COMPARISON` | 파일명을 비교할 수 없는 형태라 비교하지 않음 |
| `runtime_loading_status` | `NOT_EVALUATED` | 공통 값 |

### `spring-loading-reachability.csv`

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `depth` | `0`, `1`, … | 진입 XML은 0, import로 한 단계 내려갈 때마다 1 증가 |
| `basis` | `POTENTIAL_REACHABILITY; runtime not evaluated; <evidence_kind>` | 정적으로 도달 가능한 XML이라는 표시와 진입점의 근거 종류 |
| `runtime_loading_status` | `NOT_EVALUATED` | 공통 값 |

### `spring-loading-issues.csv`

| `status` | 뜻 |
| --- | --- |
| `INCOMPLETE_SELECTION` | extension 선택이 불완전해서 확실히 선택된 extension만 조사함 |
| `PROPERTY_FILE_ERROR` | 프로퍼티 파일을 읽지 못함 |
| `UNRESOLVED_LOADING_PROPERTY` | 로딩 프로퍼티(`*.application-context` 등)의 `${…}`를 해석하지 못함 |
| `EMPTY_CONTEXT_CONFIG` | `contextConfigLocation`이 비어 있음 |
| `REGISTRATION_NOT_ESTABLISHED` | `resources/<extension>-spring.xml`은 있지만, `<extension>.application-context` 프로퍼티도 `<extension>-application-context.xml`도 없어 그 파일을 등록하는 근거를 찾지 못함 |
| `NON_XML_CONTEXT` | `contextClass`가 XML 기반이 아닌 컨텍스트 |
| `LOADER_IMPLEMENTATION_UNVERIFIED` | 웹 로더 구현을 확인하지 못함 |
| `WEB_XML_INSPECTION_ERROR` | `web.xml` 검사 중 오류 |
| `REACHABILITY_BLOCKED_<resolution_status>` | import를 따라가다 막힘. 뒤에 붙는 값은 `spring-imports.csv`의 `resolution_status` (예: `REACHABILITY_BLOCKED_NOT_FOUND_IN_SCOPE`) |
| `NOT_FOUND_IN_SCOPE`, `UNRESOLVED_PROPERTY`, `UNSUPPORTED_LOCATION`, `AMBIGUOUS_CLASSPATH`, `TARGET_NOT_PARSED_SPRING` 등 | 진입점 위치를 해석하다 생긴 문제. `spring-import-issues.csv`와 같은 뜻 |
| `related_entry_status` 열 | `spring-loading-entrypoints.csv`와 같음. 진입점과 연결되지 않은 문제는 `NOT_APPLICABLE` |

---

## 4. 빈 선언과 참조

### `spring-beans.csv`

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `declaration_kind` | `BEAN` | `<bean>` 선언 |
| | `UTIL_LIST` / `UTIL_SET` / `UTIL_MAP` / `UTIL_CONSTANT` | `util:list`, `util:set`, `util:map`, `util:constant` 선언 |
| `reachability_status` | `POTENTIALLY_REACHABLE` | 모델링한 진입점에서 이 XML에 도달함(`potential_entry_contexts`에 컨텍스트) |
| | `NO_MODELED_ENTRY_REACHES_XML` | 어느 진입점에서도 이 XML에 도달하지 않음 → 로드되지 않을 가능성이 큼 |
| `runtime_registration_status` | `NOT_EVALUATED` | 공통 값 |
| `declared_scope`, `declared_abstract` 등 | XML 원문 | 정해진 카테고리가 아니라 속성 원문 그대로입니다 |

### `spring-bean-references.csv`

빈 선언 안의 명시적 참조 한 건이 컨텍스트별로 한 행입니다.

| 열 | 값 | 뜻 |
| --- | --- | --- |
| `relation_kind` | `ALIAS` | `<alias name="…" alias="…">`. `member_or_alias`가 alias 이름, `declared_target`이 대상 이름 |
| | `PROPERTY_REF` / `CONSTRUCTOR_REF` | `<property ref>` / `<constructor-arg ref>` |
| | `REF_BEAN` / `REF_LOCAL` / `REF_PARENT` | `<ref bean>` / `<ref local>` / `<ref parent>` |
| | `IDREF_NAME_BEAN` / `IDREF_NAME_LOCAL` / `IDREF_NAME_PARENT` | `<idref bean>` / `<idref local>` / `<idref parent>`(이름 문자열 참조) |
| | `P_NAMESPACE_REF` / `C_NAMESPACE_REF` | `p:xxx-ref` / `c:xxx-ref` 속성 |
| | `MAP_KEY_REF` / `MAP_VALUE_REF` | `<entry key-ref>` / `<entry value-ref>` |
| | `PARENT_DEFINITION` | `<bean parent="…">` |
| | `FACTORY_BEAN` | `<bean factory-bean="…">` |
| | `DEPENDS_ON` | `<bean depends-on="…">` |
| | `LOOKUP_METHOD` / `REPLACED_METHOD` | `<lookup-method bean>` / `<replaced-method replacer>` |
| | `INNER_BEAN` / `INNER_UTIL_DECLARATION` | 바깥 빈 안에 직접 들어 있는 내부 빈·util 선언 |
| `binding_status` | `SINGLE_SAME_CONTEXT_CANDIDATE` | 같은 컨텍스트에 이 이름의 선언이 하나 |
| | `MULTIPLE_SAME_CONTEXT_CANDIDATES` | 같은 컨텍스트에 이 이름의 선언이 여러 개. **덮어쓰기(override) 승자는 판정하지 않음** |
| | `NO_SAME_CONTEXT_CANDIDATE` | 같은 컨텍스트에 선언이 없음(다른 컨텍스트·스캔·동적 등록일 수 있음) |
| | `SAME_XML_CANDIDATES_ONLY` / `NO_CANDIDATE_IN_SAME_XML` | 컨텍스트를 모르는 XML이라 같은 XML 안에서만 찾은 결과(있음/없음) |
| | `DIRECT_CONTAINMENT_CANDIDATE` / `CONTAINMENT_TARGET_NOT_FOUND` | 내부 빈·util 선언의 포함 관계(찾음/못 찾음) |
| | `PARENT_CONTEXT_NOT_MODELED` | `ref parent`·`idref parent`. 부모 컨텍스트를 모델링하지 않아 연결하지 않음 |
| | `DYNAMIC_NAME_NOT_EVALUATED` | 이름에 `${…}`·`#{…}`가 있어 평가하지 않음 |
| | `EMPTY_REFERENCE_NAME` | 참조 이름이 비어 있음 |
| | `ALIAS_CYCLE_NO_CANDIDATE` | alias가 순환해서 대상 선언을 찾지 못함 |
| | `INCOMPLETE_ALIAS_TRAVERSAL` | alias 추적이 한도에 걸려 끝까지 따라가지 못함 |
| `lookup_warnings` | `ALIAS_CYCLE` | alias 순환이 있음 |
| | `ALIAS_DEPTH_LIMIT` | alias 체인이 64단계를 넘음 |
| | `ALIAS_TRAVERSAL_LIMIT` | alias 추적 작업량이 한도(4096)를 넘음 |
| | `DYNAMIC_ALIAS_TARGET_NOT_EVALUATED` | alias 대상 이름에 `${…}`·`#{…}`가 있음 |
| | `SELF_ALIAS_NOT_APPLIED` | 자기 자신을 가리키는 alias는 적용하지 않음 |
| | `FACTORY_DEREFERENCE_NOT_VALIDATED` | `&이름` 형태. FactoryBean 여부를 검증하지 않음 |
| `runtime_call_status` | `NOT_EVALUATED` | 공통 값 |
| `binding_certainty` | `STATIC_CANDIDATE_ONLY` | 공통 값 |

### `spring-bean-issues.csv`

| `status` | 뜻 |
| --- | --- |
| `BEAN_XML_INSPECTION_ERROR` | 빈 선언 수집 중 XML 오류 |
| `SKIPPED_BEAN_GENERATION_XML` | SAP 빈(DTO) 생성용 `*-beans.xml`이라 Spring 빈으로 보지 않음 |
| `UTIL_DECLARATION_INCOMPLETE` | `util:constant`에 `static-field`가 없음 |
| `ANNOTATION_PROCESSING_NOT_MODELED` | `<context:annotation-config>`. 어노테이션 주입은 분석하지 않음 |
| `COMPONENT_SCAN_NOT_MODELED` | `<context:component-scan>`. **스캔으로 등록되는 빈은 목록에 없음** |
| `CONTEXT_CONFIGURATION_NOT_MODELED` | 그 밖의 `context:` 설정 |
| `AOP_CONFIGURATION_NOT_MODELED` | `aop:` 설정(프록시 적용 대상은 분석하지 않음) |
| `INTEGRATION_FLOW_NOT_MODELED` | Spring Integration 설정(자동 등록 빈은 분석하지 않음) |
| `UTIL_TAG_NOT_MODELED` | 지원 범위(list/set/map/constant) 밖의 `util:` 태그 |
| `CUSTOM_NAMESPACE_NOT_MODELED` | 그 밖의 커스텀 네임스페이스(등록 정보는 해석하지 않음) |

`*_NOT_MODELED` 상태는 **빈 목록에서 빠졌을 수 있는 범위**를 알려 줍니다.
