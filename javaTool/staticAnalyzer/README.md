# staticAnalyzer — CallTreeExtracterAll

SAP Commerce(Hybris) 소스를 정적으로 분석해서 **Controller 엔드포인트별 호출 트리**를 텍스트 파일로 출력하는 단일 파일 Java 도구입니다.
대상 시스템을 기동하지 않습니다. `.java` 소스는 JavaParser로, Spring XML과 `extensioninfo.xml`은 DOM으로 읽습니다.

Spring XML의 bean 정의(`parent`, `property`, `constructor-arg`, `lookup-method`, `ListMergeDirective` 포함)를 해석하여, 인터페이스 호출이 실제로 어느 구현 bean으로 가는지까지 따라갑니다.

## 폴더 구성

두 파일 모두 `com` 폴더 아래에 있고 패키지는 `package com;`입니다.

| 파일 | 클래스 | 내용 |
|---|---|---|
| `com/CallTreeExtracterAll.java` | `com.CallTreeExtracterAll` | 이 문서가 설명하는 도구입니다. **가지치기 없이**(`PRUNE_ENABLED = false`) 모든 노드를 `calltreeAll.txt`로 출력합니다. |
| `com/CallTreeExtracter.java` | `com.CallTreeExtractor` | 같은 분석 로직에 가지치기를 켠 버전(`PRUNE_ENABLED = true`)입니다. `calltree.txt`로 출력합니다. |

> `CallTreeExtracter.java`는 파일명과 클래스명(`CallTreeExtractor`)이 다릅니다. 단일 파일 실행(source-file mode)에서는 문제없이 실행됩니다.
> `javac`나 Eclipse로 컴파일할 때는 이 파일이 오류가 나므로, 파일명이나 클래스명 중 하나를 바꿔 맞춰야 합니다.

## 준비물

- JDK 11 이상. 분석 대상 소스는 Java 17 문법으로 파싱합니다.
- JavaParser core jar. 코드 주석에는 `javaparser-core-3.25.10.jar`를 기준으로 적혀 있습니다. 이 폴더에는 jar가 들어 있지 않으니 따로 받아야 합니다.
- 메모리: 플랫폼 전체를 분석하므로 `-Xmx6g` 정도를 권장합니다.
- 분석 대상: `hybris/bin/platform`, `bin/modules`, `bin/custom` 같은 **소스(`.java`)가 있는 폴더**. jar 안의 클래스는 분석하지 않습니다.

## 실행 방법

### 1. 소스 안의 설정값으로 실행 (인자 없음)

파일 상단의 `EDIT HERE` 블록을 고친 다음 인자 없이 실행합니다.

```java
static final String HYBRIS_HOME = "C:/hybris";                 // bin, config의 상위 폴더
static final String[] ROOTS = { .../bin/platform, .../bin/modules, .../bin/custom }; // 순서 중요: custom을 마지막에
static final String LOCAL_EXTENSIONS = HYBRIS_HOME + "/config/localextensions.xml"; // 없으면 null
static final String CONTROLLER_ROOT = HYBRIS_HOME + "/bin/custom"; // 컨트롤러를 찾을 범위, 전체면 null
static final String FILTER = null;                               // 예: "/mystorefront/"
static final String OUT_NAME = "calltreeAll.txt";                // 작업 디렉터리에 생성
static final boolean PRUNE_ENABLED = false;                      // 가지치기 사용 여부 (All 버전 기본값: false)
static final Pattern NAME_FILTER = Pattern.compile("(?i)(facade|service|dao)");
```

```powershell
java -Xmx6g -cp javaparser-core-3.25.10.jar com/CallTreeExtracterAll.java
```

결과는 **작업 디렉터리**(`user.dir`)의 `calltreeAll.txt`에 UTF-8로 저장됩니다. Eclipse에서 실행하면 보통 프로젝트 루트가 작업 디렉터리입니다.

### 2. 명령행 인자로 실행

인자를 하나라도 주면 소스 안의 `ROOTS`, `CONTROLLER_ROOT`, `LOCAL_EXTENSIONS`, `FILTER`, 출력 파일 설정은 **모두 무시**됩니다.
위치 인자(옵션이 아닌 값)는 소스 루트입니다. 같은 FQN이 여러 루트에 있으면 **나중에 적은 루트가 우선**하므로 custom을 마지막에 적습니다.

```powershell
java -Xmx6g -cp javaparser-core-3.25.10.jar com/CallTreeExtracterAll.java --out calltreeAll.txt --base C:/hybris --localextensions C:/hybris/config/localextensions.xml --controller-root C:/hybris/bin/custom C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

| 옵션 | 설명 |
|---|---|
| `--out <file>` | 출력 파일. **생략하면 stdout**으로 출력합니다(인자 실행 시). |
| `--base <dir>` | 출력에 표시할 경로의 기준 폴더. 생략하면 `HYBRIS_HOME` 값이 그대로 쓰입니다. 코드 주석에는 "cwd"라고 적혀 있지만 실제 동작은 이렇습니다. |
| `--localextensions <xml>` | extension 로드 순서의 시작점. 생략하면 알파벳 순서와 `requires-extension`으로 정합니다. |
| `--controller-root <dir>` | 이 폴더 아래의 컨트롤러만 분석합니다. 여러 번 줄 수 있습니다. 생략하면 모든 루트가 대상입니다. |
| `--web-ext <name>` | 모든 컨트롤러의 웹 컨텍스트를 이 extension으로 고정합니다. |
| `--filter <substr>` | 파일 경로에 이 문자열이 들어간 컨트롤러만 분석합니다. |
| `--max-depth <n>` | 트리 최대 깊이. 기본값은 20입니다. |
| `--full` | 이미 전개한 서브트리도 다시 전개합니다. 기본은 `(expanded above)`로 접습니다. |
| `--include-models` | model/data/form/dto 계열 클래스도 추적합니다. |

`PRUNE_ENABLED`와 `NAME_FILTER`는 옵션으로 바꿀 수 없습니다. 소스에서 직접 수정해야 합니다.

## 처리 순서

1. **파일 수집**: 각 루트에서 `.java`, `extensioninfo.xml`, `*-spring.xml`, `*-servlet.xml`를 모읍니다.
   - 건너뛰는 폴더: `.git`, `.svn`, `node_modules`, `classes`, `eclipsebin`, `testsrc`, `.settings`
   - 이름에 `-`가 들어간 `.java`(예: `package-info.java`)도 건너뜁니다.
2. **Java 인덱스**: 각 파일의 `package` 선언으로 FQN을 만들어 FQN과 경로, 단순명과 FQN 목록을 대응시킵니다.
3. **extension 순서**: `extensioninfo.xml`의 `requires-extension`을 따라, `localextensions.xml`에 적힌 순서를 출발점으로 의존 extension을 먼저 배치합니다. 그 뒤에 남은 extension을 알파벳 순서로 붙입니다. Spring XML은 가장 깊은 extension 폴더에 속하는 것으로 봅니다.
   - 웹 XML: 경로에 `/web/webroot/`가 있거나, 파일명이 `-web-spring.xml` 또는 `-servlet.xml`로 끝나는 것
   - 그 밖의 XML은 core XML
4. **core registry**: 모든 extension의 core XML을 위 순서대로 읽습니다. 같은 bean id는 나중 것이 덮어씁니다.
5. **컨트롤러 분석**: `@Controller` 또는 `@RestController`가 붙은 클래스마다 다음을 처리합니다.
   - 그 컨트롤러가 속한 extension과 그 의존 extension들의 웹 XML로 **web registry**를 만듭니다. 부모는 core registry입니다.
   - `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`이 붙은 메서드마다 호출 트리를 만듭니다.

### 호출 대상 해석

메서드 안의 `obj.method(...)` 호출에서 `obj`의 실제 구현 클래스는 아래 순서로 찾습니다.

1. 지역 변수나 파라미터라면 선언 타입으로 bean을 찾습니다. 후보는 3개까지 허용합니다.
2. XML `property` / `p:xxx-ref` / `constructor-arg`로 주입된 bean
3. `@Resource(name=...)`. name이 없으면 필드명을 bean id로 씁니다.
4. `@Qualifier("...")`
5. 타입 기반 탐색. 후보가 여러 개면 필드명과 같은 id, 그다음 `primary="true"` bean 순으로 좁힙니다. 그래도 8개를 넘으면 `UNRESOLVED`로 표시합니다.
6. `getXxx()` 형태의 자기 호출은 `lookup-method`, 그다음 필드 `xxx`의 주입 정보로 해석합니다.
7. 대문자로 시작하는 이름은 static 호출로 봅니다. `new`, 캐스트, 메서드 반환 타입도 해석합니다.

메서드 본문은 상속 계층을 따라 **본문이 있는 첫 번째 클래스**에서 찾습니다. `super.xxx()`는 현재 클래스의 상위부터 찾습니다.

**Converter → Populator**: 2인자 `populate(...)`를 호출하는 bean이면, 그 bean의 `populators` 목록(`parent`의 `merge="true"` 목록과 `ListMergeDirective`로 추가된 것 포함)에 있는 각 populator의 `populate`도 자식 노드로 붙입니다.

### 생략되는 노드

- `--include-models`가 없으면 패키지에 `.model.`, `.data.`, `.dto.`, `.jalo.`, `.form(s).`, `.enum(s).`가 들어가거나, 클래스명이 `Model`/`Data`/`Form`/`Dto`/`Enum`으로 끝나거나, `/gensrc/` 아래에 있는 클래스
- 필드를 반환하기만 하는 getter, 문장이 하나 이하인 setter

### 가지치기 (`PRUNE_ENABLED = true`일 때만)

`CallTreeExtracterAll`은 기본값이 `false`라서 가지치기를 하지 않습니다. 매핑 메서드가 있는 모든 엔드포인트와, 위 "생략되는 노드"를 뺀 모든 호출 노드를 출력합니다.
`true`로 바꾸면 아래처럼 동작합니다(`CallTreeExtracter.java`의 기본 동작).

노드 자신의 선언 클래스나 구현 클래스의 단순명이 `NAME_FILTER`(기본값 `facade|service|dao`, 대소문자 무시)에 맞거나, 맞는 자손이 있는 가지만 남깁니다.
남는 노드가 하나도 없는 엔드포인트와, 출력할 엔드포인트가 없는 컨트롤러는 결과에서 빠집니다.

## 출력 형식

아래는 형식을 설명하기 위한 **예시**입니다(실제 실행 결과가 아님).

```text
[CONTROLLER] bin/custom/mystorefront/web/src/.../CartPageController.java  "/cart"  (bean cartPageController)
  @ [GET] /add
  CartPageController#addToCart(String, Model)  [bin/custom/.../CartPageController.java:85]
     └─ CartFacade#addToCart(String, long)  [bin/modules/.../CartFacade.java:40]  {impl=de.hybris...DefaultCartFacade}  <= xml property 'cartFacade' -> bean[cartFacade] bin/modules/.../commercefacades-spring.xml
        ├─ DefaultCommerceCartService#addToCart(CommerceCartParameter)  [...:120]  <= type-based
        └─ Converter#convert(Object)  [...]  (expanded above)
```

| 표기 | 의미 |
|---|---|
| `[CONTROLLER] 경로  매핑  (bean id)` | 컨트롤러 파일, 클래스 레벨 매핑, 해당 컨트롤러 bean id |
| `@ [HTTP] path` | 엔드포인트 메서드의 매핑 |
| `Class#method(타입...)  [경로:줄]` | 메서드가 **선언된** 클래스와 위치 |
| `{impl=FQN}` | 실제 호출을 받는 구현 클래스. 선언 클래스와 다를 때만 표시합니다. |
| `<= ...` | 구현을 결정한 근거. `xml property`, `xml constructor-arg`, `@Resource`, `@Qualifier`, `type-based`, `local var type`, `lookup-method`, `populators[...]`, `static`, `new`, `cast`, `return type of m()` |
| `-> bean[id] 파일` | 근거가 된 bean 정의와 그 XML 파일 |
| `[MULTI i/n]` | 후보 bean이 여러 개라서 모두 나열한 경우 |
| `[UNRESOLVED n candidate beans ...]` | 후보가 너무 많아 하나로 정하지 못함 |
| `[NO-BEAN-FOUND for X]` | 인터페이스/추상 타입인데 맞는 bean이 없음 |
| `(no bean; declared class)` | bean이 없어 선언된 구체 클래스를 그대로 사용 |
| `[NO-IMPL/ABSTRACT]` | 본문이 있는 구현을 찾지 못함 |
| `(expanded above)` | 같은 메서드를 이미 위에서 전개함 (`--full`이면 다시 전개) |
| `(recursion)` / `(max-depth)` | 재귀 호출이거나 최대 깊이에 도달함 |

진행 상황은 stderr에 `[info] ...` 형식으로 출력됩니다(파일 수, extension 수, bean 수, 컨트롤러 진행 수).

## 한계

정적 분석이라서 아래와 같은 경우는 실제 런타임 동작과 다를 수 있습니다.

- **XML에 정의된 bean만** registry에 들어갑니다. `component-scan`으로 등록되는 `@Component`/`@Service` bean은 인식하지 않습니다. 이런 bean은 `(no bean; declared class)`나 `NO-BEAN-FOUND`로 나옵니다.
- 오버로드는 **인자 개수로만** 구분하고 인자 타입은 비교하지 않습니다. 같은 이름과 인자 수의 메서드가 여러 개면 최대 3개까지 함께 나옵니다.
- 메서드 참조(`Foo::bar`)는 따라가지 않습니다. 람다 안의 일반 메서드 호출은 따라갑니다.
- jar로만 제공되는 클래스, AOP/인터셉터, 이벤트 리스너, 리플렉션, `${...}` 플레이스홀더로 지정한 class 속성은 추적하지 않습니다.
- 클래스 단위 파싱 결과는 최대 3000개까지 캐시합니다(LRU).
