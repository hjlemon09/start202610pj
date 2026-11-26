# occ-response-analyzer

SAP Commerce **OCC API 컨트롤러(`BaseController` 상속)의 엔드포인트마다 응답(response)에 무엇을 설정하는지** 일람으로 보여 주는 도구입니다.
응답 본문(어떤 WsDTO를, 어떤 Data에서, 어떤 `fields`로 매핑하는지), HTTP 상태, 헤더를 정적으로 구합니다.

- **활성 extension만** 일람에 넣습니다(`--extensions-csv`).
- **런타임 확인은 하지 않습니다.** 서버를 기동하지 않고 소스, 호출 그래프 CSV, Spring XML 분석 결과만 읽습니다.
- Java 21, 표준 라이브러리만 씁니다. Java 소스는 JDK의 파서(`com.sun.source`)로 읽으므로 **JRE가 아니라 JDK로 실행**해야 합니다.
- 실행 클래스는 이 저장소의 규칙대로 `execute.Execute`입니다.

## 다른 도구와의 관계

```text
extension-analyzer ──(extensions.csv: 활성 extension)─────────────┬──────────────┐
spring-bean-analyzer-from-xml ──(spring-bean-winners.csv)──┬───────┤              │
                                                           ▼       ▼              │
                         staticAnalyzer ──(controller-endpoints.csv, controller-call-edges.csv)
                                                           │                      │
                                                           ▼                      ▼
                                         occ-response-analyzer ──(occ-response-*.csv)
```

| 앞 단계 | 이 도구에서 쓰는 곳 |
|---|---|
| extension-analyzer | `--extensions-csv`. 컨트롤러 파일이 활성 extension에 속하지 않는 엔드포인트를 제외합니다. staticAnalyzer를 `--extensions-csv` 없이 실행했어도 일람은 활성 extension 기준이 됩니다. |
| spring-bean-analyzer-from-xml | (1) staticAnalyzer의 `--spring-report`로 빈 구현 클래스를 정합니다. (2) 이 도구의 `--spring-report`로 `FieldSetLevelMapping` 빈(DTO별 `BASIC`/`DEFAULT`/`FULL` → 필드 목록)을 읽습니다. |
| staticAnalyzer | OCC 엔드포인트 목록(`controller_family=occ`), 호출 대상(구현 클래스까지 해석된 것), 호출 인자 식 |
| controller-model-analyzer | 소스 읽기·호출 그래프 CSV 읽기·상수 해석·타입 추정 코드(`JavaSources`, `CallGraph`, `Expressions`, `Csv`)를 그대로 가져와 씁니다. 패키지만 `com.sapcommerce.occresponse`로 바꿨습니다. |

## 실행

1. extension-analyzer를 실행합니다.
2. spring-bean-analyzer-from-xml을 실행합니다.
3. staticAnalyzer를 `--extensions-csv`, `--spring-report`와 함께 실행합니다. `--out-dir`에 `controller-endpoints.csv`, `controller-call-edges.csv`가 만들어집니다.
4. 이 도구를 실행합니다.

```powershell
.\run.cmd --calltree C:/work/calltree --extensions-csv C:/tools/extension-analyzer/reports --spring-report C:/tools/spring-bean-analyzer-from-xml/reports C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

| 인자 | 설명 |
|---|---|
| `--calltree <폴더\|CSV>` | **필수.** staticAnalyzer의 `--out-dir` 폴더, 또는 그 안의 두 CSV 중 하나 |
| `--extensions-csv <csv\|폴더>` | extension-analyzer의 `extensions.csv`, 결과 폴더, 또는 `reports` 폴더(가장 최근 결과 폴더를 고름. 규칙은 staticAnalyzer와 같음). 생략하면 경고를 내고 staticAnalyzer가 분석한 범위를 그대로 씁니다. |
| `--base <HYBRIS_HOME>` | `extensions.csv`의 `relative_dir` 기준 폴더. 생략하면 같은 폴더 `summary.csv`의 `hybris_home`을 씁니다. |
| `--spring-report <csv\|폴더>` | spring-bean-analyzer-from-xml의 `spring-bean-winners.csv`, 결과 폴더, 또는 `reports` 폴더(가장 최근 결과). 생략하면 `fieldset_*` 열이 비고 `fieldset_kind`가 `NO_SPRING_REPORT`가 됩니다. |
| `--out <폴더>` | 결과를 만들 상위 폴더. 생략하면 작업 디렉터리의 `reports` |
| 소스 루트 (위치 인자, 여러 개) | 상수와 타입을 찾을 Java 소스 폴더. staticAnalyzer에 준 루트와 같게 주면 됩니다. 생략하면 CSV에 나온 파일만으로 찾으므로 `DEFAULT_FIELD_SET` 같은 상수가 풀리지 않을 수 있습니다. |

- `extensions.csv` 옆의 `summary.csv`가 `scan_complete=false`이면 **오류로 끝납니다.** 활성 목록을 믿을 수 없기 때문입니다(staticAnalyzer는 이때 전체 분석으로 돌아가지만, 이 도구는 활성 extension 기준을 지키려고 멈춥니다).
- 메모리가 더 필요하면 `OCC_RESPONSE_ANALYZER_OPTS`에 JVM 옵션을 넣습니다. 예: `$env:OCC_RESPONSE_ANALYZER_OPTS='-Xmx4g'`
- 결과는 실행마다 `reports/yyyyMMdd_HHmmss_고유값/` 폴더에 만들어집니다.

## 대상 엔드포인트

- staticAnalyzer의 `controller-endpoints.csv`에서 `controller_family`가 `occ`이고 `endpoint_kind`가 `REQUEST_MAPPING` 또는 `INHERITED_MAPPING`인 행
- 그중 컨트롤러 파일이 활성 extension 폴더에 속하는 것. 파일이 속한 extension은 그 파일을 포함하는 가장 안쪽 `extensioninfo.xml` 폴더로 정합니다. 어느 extension에도 속하지 않는 파일은 남깁니다(staticAnalyzer와 같은 규칙).
- 제외한 수는 `summary.csv`의 `skipped.*`에 나옵니다(`not_occ`, `inactive_extension`, `not_handler.<kind>`).

## 응답에 설정하는 것 (`target`)

| `target` | 찾는 곳 | `name` |
|---|---|---|
| `BODY` | 핸들러의 `return` 식. 반환하는 지역 변수는 그 변수에 값을 넣는 곳(초기화, 대입)마다 한 행 | 빈 값 |
| `BODY_FIELD` | 반환하는 DTO 변수(또는 그 변수를 넘긴 메서드의 인자)에 대한 `setXxx(value)` | 속성 이름 `xxx` |
| `STATUS` | `@ResponseStatus`(메서드, 없으면 컨트롤러와 상위 클래스), `response.setStatus(...)`, `ResponseEntity`의 상태 | `201 CREATED` 형식 |
| `HEADER` | `response.setHeader/addHeader/set*Header`, `setContentType` 등, `HttpHeaders`의 `add/set/setLocation…`, `ResponseEntity`의 `header(...)`, `created(uri)`(Location) | 헤더 이름(상수는 값으로 풀어 씀) |
| `COOKIE` | `response.addCookie(...)` | 빈 값 |
| `REDIRECT` / `ERROR` | `response.sendRedirect(...)` / `response.sendError(...)` | 상태 |
| `RAW_BODY` | `response.getWriter()` / `getOutputStream()` (본문을 직접 씀) | 빈 값 |

`origin`
- `DIRECT`: 핸들러 본문
- `HELPER`: 다른 메서드 안. `HttpServletResponse`, 반환 DTO, `HttpHeaders`를 넘긴 메서드와, 반환값을 그대로 돌려주는 메서드를 staticAnalyzer의 호출 간선으로 따라갑니다. 반환값을 따라가는 대상은 **컨트롤러(상위 클래스 포함)의 메서드**와 **반환 타입이 `…WsDTO`/`ResponseEntity`인 메서드**(예: `OrdersHelper#searchOrderHistory`)입니다. `via`에 경로가 나옵니다.
- `ANNOTATION`: `@ResponseStatus`

### 본문의 매핑 (`mapping_kind`)

| 값 | 판정 |
|---|---|
| `MAP` | `dataMapper.map(source, XxxWsDTO.class, fields)`. 수신 식에 `mapper`가 들어가거나(`getDataMapper()`, `dataMapper`), 호출 간선의 대상이 `DataMapper`/`ConfigurableMapper`/`MapperFacade`인 호출 |
| `MAP_AS_LIST` / `MAP_AS_SET` / … | `mapAsList(...)` 등. `dto_type`은 `List<XxxWsDTO>` |
| `MAP_INTO` | `map(source, existingDto, fields)`처럼 두 번째 인자가 클래스 리터럴이 아닌 것 |
| `NEW` | `new XxxWsDTO()`로 만든 본문(이어서 `BODY_FIELD` 행이 나옴) |
| `CALL` | 매핑이 아닌 메서드 호출의 결과(따라갈 수 없거나 따라갈 대상이 아닌 것) |

| 열 | 내용 |
|---|---|
| `source_expression`, `source_type` | 매핑의 원본 식과 그 타입(예: `product`, `ProductData`) |
| `source_call`, `source_callee`, `source_impl` | 원본을 만든 호출. 원본이 지역 변수면 사용 위치 직전의 마지막 초기화·대입을 봅니다. `source_callee`는 staticAnalyzer가 해석한 대상 메서드, `source_impl`은 구현 클래스 |
| `fields_expression`, `fields_value`, `fields_kind` | 매핑의 세 번째 인자. `REQUEST_PARAM_DEFAULT`는 요청 파라미터 `fields`의 `@RequestParam(defaultValue=…)` 값(요청에 `fields`가 없을 때의 값), `LITERAL`/`CONSTANT`는 고정 값, `NONE`은 `fields` 인자가 없는 호출(필터 없음), `DYNAMIC`은 실행해야 아는 값. 메서드 인자로 넘긴 `fields`와 상수도 따라갑니다. |
| `fieldset_kind`, `fieldset_fields`, `fieldset_bean` | `fields_value`에 해당하는 필드 목록. 아래 표 |

`fieldset_kind`

| 값 | 뜻 |
|---|---|
| `LEVEL` | `FieldSetLevelMapping`의 `levelMapping`에서 찾은 목록(예: `DEFAULT` → `BASIC,price,stock`). 목록 안의 `BASIC` 같은 레벨 이름과 하위 DTO의 필드는 펼치지 않습니다. 하위 DTO는 `occ-fieldset-mappings.csv`에서 같은 레벨을 찾아 보면 됩니다. |
| `MULTIPLE_MAPPINGS` | 같은 컨텍스트에 같은 DTO의 매핑 빈이 여럿. 모두 `|`로 나열 |
| `LEVEL_NOT_MAPPED` | DTO의 매핑은 있지만 그 레벨이 없음 |
| `NO_MAPPING` | 그 DTO의 매핑 빈을 찾지 못함 |
| `EXPLICIT_FIELDS` | `fields_value`가 레벨 이름이 아니라 필드 목록(`code,name` 등) |
| `NO_FIELDS_FILTER` | `fields` 인자가 없는 매핑 |
| `UNKNOWN_FIELDS` | `fields` 값을 정적으로 알 수 없음(기본값 없는 필수 `fields` 파라미터 포함) |
| `NO_SPRING_REPORT` | `--spring-report`를 주지 않음 |

매핑 빈은 엔드포인트의 컨텍스트(`SERVLET:…` → `WEB:<ext>` → `CORE` → `GLOBAL`)에서 가장 가까운 곳의 것을 씁니다. DTO 클래스는 소스에서 FQN을 찾을 수 있으면 FQN으로, 아니면 단순명으로 비교합니다.

`conditional`=`true`는 그 설정이 `if`/`switch`/반복문/`catch`/람다/`&&`·`||` 오른쪽/삼항 연산자 안에 있다는 뜻입니다(경로 중간 포함). 조건이 실제로 참인지는 판정하지 않습니다.

## 결과 파일

| 파일 | 내용 |
|---|---|
| `occ-response-items.csv` | **일람.** 엔드포인트마다 응답 설정 한 행씩. 엔드포인트 정보(`extension`, `http_method`, `path`, `response_kind`), `origin`, `target`, `name`, `name_kind`, `value_expression`, `value_type`, 매핑 열, `fieldset_*`, `conditional`, `via`, 쓴 위치 |
| `occ-response-endpoints.csv` | 엔드포인트 한 개당 한 행. `http_method`, `path`(클래스와 메서드의 매핑을 합친 경로, 상수는 값으로), `return_type`, `response_kind`, `http_status`(상태 행을 모은 것, 없으면 `200 OK (default)`), `body_types`, `source_calls`, `fields_parameter`, `fields_default`, 행 수, 문제 수 |
| `occ-response-issues.csv` | 확인이 필요한 곳(아래) |
| `occ-fieldset-mappings.csv` | 읽은 `FieldSetLevelMapping` 빈 전체(컨텍스트, 빈, DTO, 레벨, 필드, XML). 하위 DTO의 필드를 찾을 때 씁니다. |
| `summary.csv` | 입력 경로, 활성 extension 수, 제외 수, 개수, 경고 |

`response_kind`

| 값 | 판정 |
|---|---|
| `BODY` | `@ResponseBody`(메서드) 또는 `@RestController`/`@ResponseBody`(컨트롤러나 상위 클래스) |
| `RESPONSE_ENTITY` | `ResponseEntity`/`HttpEntity` 반환 |
| `VOID` | `void` |
| `VIEW` | 위에 해당하지 않음. 반환값은 뷰 이름이므로 `BODY` 행을 만들지 않습니다. |

`occ-response-issues.csv`의 `issue`

| 값 | 뜻 |
|---|---|
| `RESPONSE_PASSED_TO_UNTRACED_CALL` | `HttpServletResponse`·반환 DTO·`HttpHeaders`를 넘겼지만 따라갈 수 없는 호출(호출 간선 없음, `UNRESOLVED`, `NO_BODY`). **그 안에서 설정하는 것은 일람에 빠져 있습니다.** |
| `BODY_FROM_UNTRACED_CALL` | 본문이 `…WsDTO`/`ResponseEntity`를 반환하는 호출인데 따라갈 수 없음 |
| `DYNAMIC_HEADER_NAME`, `DYNAMIC_STATUS`, `DYNAMIC_FIELDS` | 헤더 이름, 상태, `fields` 값을 정적으로 알 수 없음 |
| `UNRESOLVED_CONSTANT` | 상수 값을 찾지 못함. 소스 루트를 더 주면 풀릴 수 있습니다. |
| `BULK_HEADERS` | 헤더를 한꺼번에 넣음(`addAll`, `headers(h)` 등) |
| `METHOD_NOT_FOUND` | CSV가 가리키는 메서드를 소스에서 찾지 못함(소스가 바뀌었으면 staticAnalyzer를 다시 실행) |
| `DEPTH_LIMIT` | 추적 깊이 40을 넘음 |

## 한계

- **정적 분석입니다.** 실제 값, 조건의 참·거짓, 실행 여부는 판정하지 않습니다. 실제로 내려가는 JSON은 Orika 매핑 설정, 커스텀 converter, `fields`의 하위 레벨 전개에 따라 달라집니다.
- 호출 그래프는 staticAnalyzer의 결과 그대로입니다. 그 한계(XML에 없는 빈, AOP, 리플렉션, 생략되는 model/data 계열 클래스)가 이 도구의 한계가 됩니다.
- 활성 extension 필터는 **엔드포인트(컨트롤러 파일) 단위**입니다. 호출 대상의 해석은 staticAnalyzer가 한 그대로이므로, staticAnalyzer도 `--extensions-csv`로 실행하는 것이 좋습니다.
- `@ExceptionHandler`·`@ControllerAdvice`·`HandlerInterceptor`·`ResponseBodyAdvice`가 바꾸는 응답(에러 응답, 공통 헤더)은 다루지 않습니다.
- `FieldSetLevelMapping`은 승자 선언 안의 `dtoClass`와 `levelMapping`(`<map><entry>`)만 읽습니다. 부모 빈에서 물려받은 속성, `merge="true"`, 다른 방식(코드)으로 등록한 매핑은 반영하지 않습니다. 매핑 빈은 `effective_class` 또는 빈 이름이 `FieldSetLevelMapping`으로 끝나는 것만 찾습니다.
- `BODY_FIELD`는 `setXxx(값)` 한 인자 setter만 봅니다. `getXxx().add(...)`, 빌더, 리플렉션으로 채우는 경우는 나오지 않습니다.
- 반환값을 따라가는 메서드는 위 「`origin`」의 조건에 맞는 것뿐입니다. 그 밖의 메서드의 반환값은 `CALL` 행으로 남습니다.
- 실제 SAP Commerce 설치본에서는 아직 확인하지 않았습니다.

## 소스 구조와 검증

| 파일 | 내용 |
|---|---|
| `src/execute/Execute.java` | 실행 클래스 |
| `OccResponseAnalyzer.java` | 전체 흐름, 대상 선택(OCC·활성 extension), HTTP 메서드와 경로, `fields` 파라미터, `@ResponseStatus`, 응답 종류, 엔드포인트별 집계 |
| `ResponseScanner.java` | 반환 식 분류(DataMapper, `ResponseEntity`, `new`, helper), 본문 변수의 setter, `HttpServletResponse`/`HttpHeaders` 쓰기, 호출 간선 추적 |
| `HttpStatuses.java` | `HttpStatus.X`, `SC_X`, 숫자, 상수 → `201 CREATED` |
| `ActiveExtensions.java` | `extensions.csv` 읽기(최신 결과 선택, `summary.csv` 확인), 파일의 extension 판정 |
| `FieldSetMappings.java` | `spring-bean-winners.csv`의 승자 선언에서 `FieldSetLevelMapping` 읽기(JAR 안 XML 포함), 컨텍스트별 찾기 |
| `ReportWriter.java` | 결과 CSV |
| `CallGraph.java`, `JavaSources.java`, `Expressions.java`, `Csv.java` | controller-model-analyzer에서 가져온 코드 |

`test.cmd`가 `test/.../OccResponseAnalyzerTest`를 실행합니다. 임시 폴더에 extension 폴더 구조, 예제 OCC 컨트롤러, staticAnalyzer 형식의 CSV, extension-analyzer 결과(오래된 것과 최신 것), Spring 보고서와 XML을 만들어 다음을 확인합니다.
- 비활성 extension과 accelerator 엔드포인트 제외, 최신 결과 폴더 선택
- 클래스·메서드 매핑을 합친 경로(상수, `RequestMethod`)
- `map`/`mapAsList`의 DTO 타입, 원본 Data 타입과 그것을 만든 facade 호출, `fields` 기본값(상수 경유)과 field set 레벨의 필드 목록
- 자기 helper 메서드와 helper 빈의 반환값 추적, 조건부 반환, helper로 넘긴 `fields`
- `BaseController` helper의 헤더, 반환 DTO의 setter, `@ResponseStatus`, `void`
- `ResponseEntity` 생성자와 빌더(`created`, `header`), `HttpHeaders`, `setStatus`
- 결과 파일
