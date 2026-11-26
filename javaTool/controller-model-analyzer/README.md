# controller-model-analyzer

SAP Commerce Accelerator의 **컨트롤러 엔트리포인트별로 모델(Model)에 어떤 값을 넣는지** 일람으로 보여 주는 도구입니다.
속성 이름, 값의 식, 값의 타입을 정적으로 구합니다. 실행 중의 실제 값은 구하지 않습니다.

- Java 21, 표준 라이브러리만 씁니다. Java 소스는 JDK에 들어 있는 파서(`com.sun.source`)로 읽습니다. **JRE가 아니라 JDK로 실행**해야 합니다.
- 실행 클래스는 이 저장소의 규칙대로 `execute.Execute`입니다.

## 다른 도구와의 관계

이 도구는 컨트롤러를 직접 찾지 않습니다. [staticAnalyzer](../staticAnalyzer/README.md)가 만든 **호출 그래프 CSV**를 입력으로 씁니다.

```text
extension-analyzer ──(extensions.csv: 활성 extension)──┐
spring-bean-analyzer-from-xml ──(빈 승자 보고서)───────┤
                                                       ▼
                     staticAnalyzer ──(controller-endpoints.csv, controller-call-edges.csv)
                                                       ▼
                             controller-model-analyzer ──(controller-model-*.csv)
```

| 앞 단계 | 이 도구에 주는 것 |
|---|---|
| extension-analyzer | 분석 범위(활성 extension). staticAnalyzer의 `--extensions-csv`로 반영됩니다. |
| spring-bean-analyzer-from-xml | 컨텍스트별 빈 승자. staticAnalyzer의 `--spring-report`로 반영됩니다. 실제로 쓰이는 컨트롤러 빈과 구현 클래스, `beforeViewHandlers` 목록이 여기서 정해집니다. |
| staticAnalyzer | 엔트리포인트 목록, 각 호출의 대상 메서드(구현 클래스까지 해석), 호출 인자 식 |

이 도구가 하는 일은, 엔트리포인트의 모델 객체가 어느 메서드로 넘어가는지를 호출 그래프를 따라 추적하고, 그 메서드 본문에서 모델에 쓰는 문장을 찾는 것입니다.

## 실행

1. staticAnalyzer를 실행합니다. `--out-dir`에 텍스트 호출 트리와 함께 `controller-endpoints.csv`, `controller-call-edges.csv`가 만들어집니다.
2. 이 도구를 실행합니다.

```powershell
.\run.cmd --calltree C:/work/calltree C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

| 인자 | 설명 |
|---|---|
| `--calltree <폴더\|CSV>` | **필수.** staticAnalyzer의 `--out-dir` 폴더, 또는 그 안의 두 CSV 중 하나 |
| `--out <폴더>` | 결과를 만들 상위 폴더. 생략하면 작업 디렉터리의 `reports` |
| 소스 루트 (위치 인자, 여러 개) | 상수와 타입을 찾을 때 쓰는 Java 소스 폴더. staticAnalyzer에 준 루트와 같게 주면 됩니다. 생략하면 CSV에 나온 파일만으로 찾으므로, 다른 클래스의 상수(`WebConstants.X` 등)가 `UNRESOLVED_CONSTANT`가 되기 쉽습니다. |

- 메모리가 더 필요하면 `CONTROLLER_MODEL_ANALYZER_OPTS`에 JVM 옵션을 넣습니다. 예: `$env:CONTROLLER_MODEL_ANALYZER_OPTS='-Xmx4g'`
- 결과는 실행마다 `reports/yyyyMMdd_HHmmss_고유값/` 폴더에 만들어집니다.

## 모델에 값이 들어가는 경로와 판정

| `origin` | 내용 |
|---|---|
| `DIRECT` | 엔트리포인트 본문에서 직접 쓴 것. `model.addAttribute(...)` 등 |
| `HELPER` | 모델을 인자로 넘긴 메서드 안에서 쓴 것. `storeCmsPageInModel(model, page)`, `GlobalMessages.addErrorMessage(model, ...)` 등. 상위 클래스의 메서드, static 유틸리티, 빈의 메서드 모두 staticAnalyzer가 해석한 대상을 따라갑니다. |
| `BOUND_PARAMETER` | Spring이 핸들러 인자를 모델에 넣는 것. `@ModelAttribute` 인자와, 어노테이션 없는 폼 객체 같은 단순하지 않은 타입의 인자 |
| `MODEL_ATTRIBUTE_METHOD` | 컨트롤러(상위 클래스 포함)의 `@ModelAttribute` 메서드. 그 컨트롤러의 모든 핸들러 앞에 실행됩니다. 반환값이 있으면 그 값이, `Model` 인자가 있으면 그 안의 쓰기가 나옵니다. |
| `BEFORE_VIEW_HANDLER` | 같은 웹 컨텍스트의 `beforeViewHandlers` 목록에 있는 `BeforeViewHandler`가 `ModelAndView`에 넣는 것. 리다이렉트와 `@ResponseBody` 응답에는 붙이지 않습니다. |

모델로 보는 변수
- 시작 메서드의 `Model`, `ModelMap`, `ExtendedModelMap`, `ConcurrentModel`, `RedirectAttributes`, `ModelAndView` 타입 인자. 핸들러의 `Map` 인자도 모델로 봅니다.
- 그 변수를 다른 지역 변수에 넣은 것, `model.asMap()`과 `modelAndView.getModel()`의 결과, `new ModelAndView(...)`

모델에 쓰는 문장

| 대상 | 문장 | `target` |
|---|---|---|
| Model / ModelMap | `addAttribute(name, value)`, `addAttribute(value)`, `put(name, value)` | `MODEL` |
| `asMap()` 등 Map | `put`, `putIfAbsent` | `MODEL` |
| RedirectAttributes | `addFlashAttribute(...)` | `FLASH` (리다이렉트 다음 요청의 모델) |
| RedirectAttributes | `addAttribute(...)` | `REDIRECT_PARAM` (리다이렉트 URL의 쿼리 파라미터) |
| ModelAndView | `addObject(...)`, `new ModelAndView(view, name, value)` | `MODEL` |
| 위 모두 | `addAllAttributes`, `mergeAttributes`, `putAll`, `addAllObjects`, `new ModelAndView(view, map)` | 이름을 알 수 없으므로 `attribute_name`=`*`, `name_kind`=`BULK` |

속성 이름 (`name_kind`)

| 값 | 뜻 |
|---|---|
| `LITERAL` | 문자열 리터럴 |
| `CONSTANT` | 상수 필드(상위 클래스, 상수 인터페이스, static import 포함), 상수끼리의 `+` 연결, 상수로 초기화한 지역 변수. 호출할 때 상수를 넘긴 인자도 포함합니다(`name_expression`에 `(argument)` 표시). 예: `GlobalMessages`가 holder 이름을 인자로 받는 경우 |
| `TYPE_DERIVED` | 이름 없이 넣은 값. Spring 규칙(`Conventions.getVariableName`)대로 타입의 단순명을 소문자로 시작하게 바꾼 이름입니다. 컬렉션과 배열은 요소 타입 + `List`. 타입을 모르면 이름은 비어 있습니다. |
| `DYNAMIC` | 변수나 메서드 호출 결과 등 실행해야 알 수 있는 이름 |
| `UNRESOLVED_CONSTANT` | 상수처럼 보이지만 소스에서 값을 찾지 못한 것. 소스 루트를 더 주면 풀릴 수 있습니다. |
| `BULK` | 여러 속성을 한꺼번에 넣는 호출 |

값의 타입 (`value_type`)은 소스에 적힌 타입 텍스트입니다(제네릭 포함, 패키지 없음).
- 리터럴, `new`, 캐스트, 지역 변수·인자·필드의 선언 타입
- 메서드 호출은 staticAnalyzer가 해석한 호출 대상의 반환 타입. 구현이 여러 개면 `A | B`로 적습니다. 호출 그래프에 없는 메서드(데이터 클래스의 getter 등)는 수신 객체의 타입에서 같은 이름·인자 수의 메서드를 찾아 반환 타입을 씁니다.
- 찾지 못하면 비워 둡니다. 소스가 없는 클래스(jar로만 있는 클래스, 생성된 모델 클래스가 루트에 없을 때)의 메서드가 대표적입니다.

`conditional`=`true`는 그 쓰기가 `if`/`switch`/반복문/`catch`/람다/`&&`·`||` 오른쪽/삼항 연산자 안에 있다는 뜻입니다. 호출 경로 중간이 조건부여도 `true`입니다. 실제로 그 조건이 참인지는 판정하지 않습니다.

`response` (핸들러만)

| 값 | 판정 |
|---|---|
| `VIEW` | 뷰 이름이나 `ModelAndView`를 반환 |
| `REDIRECT` / `FORWARD` | 반환 값이 `redirect:` / `forward:`로 시작하는 상수 식. 상수로 풀리지 않으면 식에 `REDIRECT`/`FORWARD`라는 글자가 있는지로 판정 |
| `VIEW|REDIRECT` 등 | 반환문마다 다른 경우 |
| `RESPONSE_BODY` | `@ResponseBody`, `@RestController`, `ResponseEntity`/`HttpEntity` 반환 |
| `VOID` | `void` 핸들러 |

## 결과 파일

| 파일 | 내용 |
|---|---|
| `controller-model-attributes.csv` | **일람.** 엔트리포인트(`REQUEST_MAPPING`, `INHERITED_MAPPING`, `CMS_COMPONENT`)마다 모델 속성 한 행씩. 열: 엔트리포인트 정보, `response`, `origin`, `target`, `attribute_name`, `name_kind`, `name_expression`, `value_expression`, `value_type`, `conditional`, `via`(시작 메서드부터 쓴 메서드까지의 경로), 쓴 위치(`declaring_class`, `source_file`, `source_line`) |
| `controller-model-endpoints.csv` | staticAnalyzer가 넘긴 모든 시작 메서드와 속성 수, 문제 수. `MODEL_ATTRIBUTE_METHOD`와 `BEFORE_VIEW_HANDLER`는 여기에만 자기 행이 있고, 속성은 해당 핸들러의 행으로 들어갑니다. |
| `controller-model-issues.csv` | 확인이 필요한 곳(아래) |
| `summary.csv` | 입력 경로, 개수 |

`endpoint_kind`

| 값 | 뜻 |
|---|---|
| `REQUEST_MAPPING` | 컨트롤러 클래스에 선언된 매핑 메서드 |
| `INHERITED_MAPPING` | 상위 클래스에 선언된 매핑 메서드(하위 클래스가 재정의하면 그 본문). CMS 컴포넌트 컨트롤러의 공통 요청 처리 메서드가 여기에 나옵니다(상위 클래스 소스가 루트에 있을 때). |
| `CMS_COMPONENT` | CMS 컴포넌트 컨트롤러의 private가 아닌 메서드(`fillModel` 등). 핸들러가 아니므로 `@ModelAttribute`·BeforeViewHandler 행은 붙지 않습니다. |

`controller-model-issues.csv`의 `issue`

| 값 | 뜻 |
|---|---|
| `MODEL_PASSED_TO_UNTRACED_CALL` | 모델을 넘겼지만 따라갈 수 없는 호출. 호출 그래프에 없거나(`no call edge`: 소스가 없거나 staticAnalyzer가 생략한 호출), 대상이 미확정(`UNRESOLVED`)이거나 본문이 없는(`NO_BODY`) 경우. **이 호출 안에서 넣는 속성은 일람에 빠져 있습니다.** |
| `DYNAMIC_ATTRIBUTE_NAME` | 이름을 정적으로 알 수 없음 |
| `UNRESOLVED_CONSTANT` | 상수 값을 찾지 못함 |
| `TYPE_DERIVED_NAME_UNKNOWN` | 이름 없이 넣었는데 값의 타입을 몰라 이름을 정할 수 없음 |
| `BULK_ATTRIBUTES` | 여러 속성을 한꺼번에 넣음 |
| `METHOD_NOT_FOUND` | CSV가 가리키는 메서드를 소스에서 찾지 못함(소스가 바뀌었으면 staticAnalyzer를 다시 실행) |
| `DEPTH_LIMIT` | 모델 전달 깊이 40을 넘음 |

## 한계

- **정적 분석입니다.** 실제 값, 조건의 참·거짓, 실행 여부는 판정하지 않습니다.
- 호출 그래프는 staticAnalyzer의 결과 그대로입니다. 그 한계(XML에 없는 빈, AOP, 리플렉션 등)가 그대로 이 도구의 한계가 됩니다. staticAnalyzer가 생략하는 model/data/form 계열 클래스의 메서드로 모델을 넘기면 `MODEL_PASSED_TO_UNTRACED_CALL`이 됩니다.
- 모델을 필드에 저장했다가 다른 곳에서 쓰는 경우, 람다 안에서 다른 메서드로 넘기는 경우의 일부는 따라가지 못할 수 있습니다.
- `BEFORE_VIEW_HANDLER`는 이름이 `beforeViewHandlers`인 속성(리스트 또는 `util:list` 참조, `ListMergeDirective` 포함)을 가진 빈에서 찾습니다. 다른 이름으로 등록하는 구성이면 나오지 않습니다. `HandlerInterceptor`의 `postHandle`, `@ControllerAdvice`의 `@ModelAttribute`는 다루지 않습니다.
- CMS 컴포넌트 컨트롤러의 상위 클래스가 컴포넌트 속성을 자동으로 넣는 처리는, 그 상위 클래스 소스가 루트에 있고 그 안의 쓰기가 위 규칙에 맞을 때만 나옵니다.
- 핸들러 인자 중 어노테이션 없는 인자는 Spring 기본 규칙(단순 타입이 아니면 `@ModelAttribute`)으로 판정합니다. 커스텀 `HandlerMethodArgumentResolver`가 처리하는 인자도 `BOUND_PARAMETER`로 나올 수 있습니다.
- 실제 SAP Commerce 설치본에서는 아직 확인하지 않았습니다.

## 소스 구조와 검증

| 파일 | 내용 |
|---|---|
| `src/execute/Execute.java` | 실행 클래스 |
| `ControllerModelAnalyzer.java` | 전체 흐름, 핸들러 인자, `@ModelAttribute` 반환값, 응답 종류, 핸들러별 행 조립 |
| `CallGraph.java` | staticAnalyzer CSV 읽기, 호출문과 호출 간선의 대응 |
| `ModelScanner.java` | 모델 변수 추적, 쓰기 수집, 호출 간선을 따라 다른 메서드로 이동 |
| `Expressions.java` | 속성 이름, 상수 값, 값 타입 |
| `JavaSources.java` | 소스 파싱, 타입·필드·메서드 찾기 |
| `ReportWriter.java`, `Csv.java` | 결과 CSV(UTF-8 BOM, CRLF, 모든 값 따옴표) |

`test.cmd`가 `test/.../ControllerModelAnalyzerTest`를 실행합니다. 예제 소스와 staticAnalyzer 형식의 CSV를 임시 폴더에 만들어 다음을 확인합니다.
- 직접 쓰기, 상위 클래스 helper, static 유틸리티(인자로 넘긴 상수 이름 포함), 상수 인터페이스, 타입에서 정한 이름
- 호출 간선의 반환 타입, 간선 없는 자기 메서드의 반환 타입, `if` 안의 조건부 쓰기
- 핸들러 인자(`@RequestParam` 제외), `@ModelAttribute` 메서드, BeforeViewHandler, 리다이렉트와 flash, `ModelAndView`, CMS 컴포넌트 메서드, 다른 컨텍스트와의 경계
- 문제 기록(동적 이름, 따라갈 수 없는 호출), 결과 파일
