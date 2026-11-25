# staticAnalyzer — CallTreeExtractor

SAP Commerce(Hybris) 소스를 정적으로 분석해서 **Controller 엔드포인트별 호출 트리**를 텍스트 파일로 출력하는 단일 파일 Java 도구입니다.
분석 대상 extension 안의 컨트롤러 중 **accelerator 계열(`AbstractController` 상속)**과 **OCC 계열(`BaseController` 상속)**을 각각 다른 파일로 출력합니다(아래 「대상 컨트롤러와 출력 파일」).
Accelerator의 **CMS 컴포넌트 컨트롤러**(`AbstractAcceleratorCMSComponentController` 상속)는 매핑 어노테이션이 없는 `fillModel` 같은 메서드도 분석합니다.
대상 시스템을 기동하지 않습니다. `.java` 소스는 JavaParser로, Spring XML과 `extensioninfo.xml`은 DOM으로 읽습니다.

Spring XML의 bean 정의(`parent`, `property`, `constructor-arg`, `lookup-method`, `ListMergeDirective` 포함)를 해석하여, 인터페이스 호출이 실제로 어느 구현 bean으로 가는지까지 따라갑니다.

[`activated-extension-extractor`](../activated-extension-extractor/README.md)의 결과 `extensions.csv`를 넘기면 **활성 extension만** 분석합니다. 자세한 내용은 아래 「활성 extension만 분석」을 참고하세요.

## 폴더 구성

| 파일 | 클래스 | 내용 |
|---|---|---|
| `com/CallTreeExtractor.java` | `com.CallTreeExtractor` (`package com;`) | 이 문서가 설명하는 도구입니다. 기본값은 **가지치기 없이**(`PRUNE_ENABLED = false`) 모든 노드를 출력합니다(`calltree_accelerator_all.txt`, `calltree_occ_all.txt`). `true`로 바꾸면 가지치기한 결과를 `_pruned` 파일로 출력합니다. |

## 대상 컨트롤러와 출력 파일

분석 대상 extension 안에 있는 `@Controller`/`@RestController` 클래스 중에서, 상속 관계로 아래처럼 나눠서 출력합니다.
- 분석 대상 extension은 `--extensions-csv`를 쓰면 활성 extension과 `--include-ext`로 지정한 extension이고, 쓰지 않으면 루트 아래 전체입니다.
- 이전처럼 `bin/custom`으로 범위를 제한하지 않습니다. `CONTROLLER_ROOT` 기본값이 `null`입니다.

| 상속 관계 (간접 상속 포함) | 계열 | `PRUNE_ENABLED = false` | `PRUNE_ENABLED = true` |
|---|---|---|---|
| `AbstractController` (`org.springframework.*`의 같은 이름 클래스는 제외) | accelerator 스토어프론트 | `calltree_accelerator_all.txt` | `calltree_accelerator_pruned.txt` |
| `BaseController` | OCC (웹서비스 API) | `calltree_occ_all.txt` | `calltree_occ_pruned.txt` |

- 상속 여부는 `extends` 절을 따라 올라가면서 **단순 클래스명**으로 판정합니다. 예를 들어 `AbstractPageController`, `AbstractCMSComponentController`, `AbstractAcceleratorCMSComponentController`를 거쳐 `AbstractController`에 닿으면 accelerator 계열입니다.
- `import org.springframework.web.servlet.mvc.AbstractController`처럼 Spring MVC의 `AbstractController`를 상속한 경우는 대상이 아닙니다.
- 둘 중 어디에도 해당하지 않는 컨트롤러는 출력하지 않고 건너뜁니다. 건너뛴 수는 진행 로그에 나옵니다.
- **중간 상위 클래스의 소스를 찾지 못하면** 더 올라갈 수 없어서 건너뜁니다. 예를 들어 `acceleratorstorefrontcommons`가 분석 대상에 없는 경우입니다. 이때는 그 상위 클래스 이름과 컨트롤러 수를 `[warn]` 로그로 출력합니다. 대상에 넣어야 하는 컨트롤러라면 해당 extension을 `--include-ext`로 추가하거나 루트에 포함하세요.
- 두 파일은 항상 함께 만들어집니다. 해당하는 컨트롤러가 없으면 빈 파일이 됩니다.

## 준비물

- JDK 11 이상. 분석 대상 소스는 **Java 21 문법**(`LanguageLevel.JAVA_21`)으로 파싱합니다. Java 17 이하 소스도 그대로 파싱됩니다.
- JavaParser core jar **3.26.0 이상**. `JAVA_21`은 3.26.0부터 있어서 3.25.x jar로는 컴파일되지 않습니다. 동작을 확인한 버전은 `javaparser-core-3.27.1.jar`입니다. 이 폴더에는 jar가 들어 있지 않으니 따로 받아야 합니다.
- 메모리: 플랫폼 전체를 분석하므로 `-Xmx6g` 정도를 권장합니다.
- 분석 대상: `hybris/bin/platform`, `bin/modules`, `bin/custom` 같은 **소스(`.java`)가 있는 폴더**. jar 안의 클래스는 분석하지 않습니다.

## 실행 방법

### 1. 소스 안의 설정값으로 실행 (인자 없음)

파일 상단의 `EDIT HERE` 블록을 고친 다음 인자 없이 실행합니다.

```java
static final String HYBRIS_HOME = "C:/hybris";                 // bin, config의 상위 폴더
static final String[] ROOTS = { .../bin/platform, .../bin/modules, .../bin/custom }; // 순서 중요: custom을 마지막에
static final String LOCAL_EXTENSIONS = HYBRIS_HOME + "/config/localextensions.xml"; // 없으면 null
static final String CONTROLLER_ROOT = null;                      // 컨트롤러 범위를 더 좁힐 때만 지정. null이면 분석 대상 extension 전체
static final String FILTER = null;                               // 예: "/mystorefront/"
static final String EXTENSIONS_CSV = null;                       // 예: "C:/work/reports/20261125_101500_xxx/extensions.csv", 전체면 null
static final String[] INCLUDE_EXTENSIONS = {};                   // EXTENSIONS_CSV 사용 시 추가로 포함할 extension. 예: {"myaddon", "mytest"}
static final boolean PRUNE_ENABLED = false;                      // 가지치기 사용 여부 (기본값: false)
static final String OUT_DIR = null;                              // 출력 폴더. null이면 작업 디렉터리
static final String OUT_SUFFIX = PRUNE_ENABLED ? "pruned" : "all"; // calltree_accelerator_<suffix>.txt, calltree_occ_<suffix>.txt
static final Pattern NAME_FILTER = Pattern.compile("(?i)(facade|service|dao)");
```

```powershell
java -Xmx6g -cp javaparser-core-3.27.1.jar com/CallTreeExtractor.java
```

결과 두 파일은 `OUT_DIR`(기본값은 **작업 디렉터리** `user.dir`)에 UTF-8로 저장됩니다. 파일명은 위 「대상 컨트롤러와 출력 파일」과 같습니다.
Eclipse에서 실행하면 보통 프로젝트 루트가 작업 디렉터리입니다.

### 2. 명령행 인자로 실행

인자를 하나라도 주면 소스 안의 `ROOTS`, `CONTROLLER_ROOT`, `LOCAL_EXTENSIONS`, `FILTER`, `EXTENSIONS_CSV`, `INCLUDE_EXTENSIONS`, `OUT_DIR` 설정은 **모두 무시**됩니다. 출력 폴더는 `--out-dir`로 지정하고, 생략하면 작업 디렉터리입니다. 파일명 규칙(`_all`/`_pruned`)은 인자 실행에서도 같습니다.
위치 인자(옵션이 아닌 값)는 소스 루트입니다. 같은 FQN이 여러 루트에 있으면 **나중에 적은 루트가 우선**하므로 custom을 마지막에 적습니다.

```powershell
java -Xmx6g -cp javaparser-core-3.27.1.jar com/CallTreeExtractor.java --out-dir C:/work/calltree --base C:/hybris --localextensions C:/hybris/config/localextensions.xml --extensions-csv C:/work/reports/20261125_101500_xxx/extensions.csv C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

| 옵션 | 설명 |
|---|---|
| `--out-dir <dir>` | 두 출력 파일을 저장할 폴더. 없으면 만듭니다. 생략하면 작업 디렉터리입니다. 예전의 `--out <file>`은 없어졌습니다(주면 오류 메시지를 출력하고 끝납니다). |
| `--base <dir>` | 출력에 표시할 경로의 기준 폴더. 생략하면 `HYBRIS_HOME` 값이 그대로 쓰입니다. 코드 주석에는 "cwd"라고 적혀 있지만 실제 동작은 이렇습니다. |
| `--localextensions <xml>` | extension 로드 순서의 시작점. 생략하면 알파벳 순서와 `requires-extension`으로 정합니다. |
| `--controller-root <dir>` | 컨트롤러 범위를 더 좁힐 때만 씁니다. 이 폴더 아래의 컨트롤러만 분석하며, 여러 번 줄 수 있습니다. 생략하면 분석 대상 extension 전체가 대상입니다. |
| `--web-ext <name>` | 모든 컨트롤러의 웹 컨텍스트를 이 extension으로 고정합니다. |
| `--filter <substr>` | 파일 경로에 이 문자열이 들어간 컨트롤러만 분석합니다. |
| `--max-depth <n>` | 트리 최대 깊이. 기본값은 20입니다. |
| `--full` | 이미 전개한 서브트리도 다시 전개합니다. 기본은 `(expanded above)`로 접습니다. |
| `--include-models` | model/data/form/dto 계열 클래스도 추적합니다. |
| `--extensions-csv <csv>` | `activated-extension-extractor`의 `extensions.csv`. 활성 extension만 분석합니다(아래 참고). |
| `--include-ext <name>` | `--extensions-csv`와 함께 쓸 때, 이 extension도 분석 대상에 추가합니다. 여러 번 줄 수 있고 쉼표로 묶어도 됩니다(`--include-ext a,b --include-ext c`). |

`PRUNE_ENABLED`와 `NAME_FILTER`는 옵션으로 바꿀 수 없습니다. 소스에서 직접 수정해야 합니다.

## 활성 extension만 분석 (`extensions.csv`)

먼저 [`activated-extension-extractor`](../activated-extension-extractor/README.md)를 같은 `HYBRIS_HOME`에 대해 실행합니다. 그 결과 폴더의 `extensions.csv`를 `--extensions-csv`(또는 `EXTENSIONS_CSV`)로 지정합니다.

```powershell
java -Xmx6g -cp javaparser-core-3.27.1.jar com/CallTreeExtractor.java --out-dir C:/work/calltree --base C:/hybris --localextensions C:/hybris/config/localextensions.xml --extensions-csv C:/work/reports/20261125_101500_xxx/extensions.csv C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

**동작**
- 루트를 전부 탐색한 다음, **활성 extension 폴더에 속한 파일만 남깁니다.** 대상은 `.java`, `extensioninfo.xml`, `*-spring.xml`, `*-servlet.xml`입니다.
  - 파일이 어느 extension에 속하는지는, 그 파일을 포함하는 **가장 안쪽** `extensioninfo.xml` 폴더로 정합니다.
  - 어느 extension 폴더에도 속하지 않는 파일은 그대로 둡니다.
- 활성 extension 폴더는 `extensions.csv`의 `relative_dir`을 `--base`(기본값 `HYBRIS_HOME`)에 붙여서 정합니다. `relative_dir`이 비어 있으면 `xml_path`의 폴더를 씁니다. 그래서 **`--base`는 extractor를 실행할 때 쓴 `HYBRIS_HOME`과 같은 구조여야 합니다.**
- 같은 이름의 extension이 여러 개 있으면 extractor가 고른 폴더만 남습니다. 백업 복사본 같은 다른 폴더는 빠집니다.
- extension 로드 순서는 `localextensions.xml` 순서, `extensions.csv`의 extension 순서, 알파벳 순서 순으로 시작점을 잡고, `requires-extension` 대상을 먼저 배치합니다. 필터를 쓰면 남은 extension이 모두 활성 extension이라서, 비활성 extension이 끼어들지 않습니다.

**수동으로 extension 추가 (`--include-ext` / `INCLUDE_EXTENSIONS`)**

`extensions.csv`에 없는 extension도 이름을 지정하면 분석 대상에 넣습니다. 여러 개를 지정할 수 있습니다.

```powershell
java -Xmx6g -cp javaparser-core-3.27.1.jar com/CallTreeExtractor.java ... --extensions-csv C:/work/reports/xxx/extensions.csv --include-ext myaddon,mytest --include-ext myextra C:/hybris/bin/platform C:/hybris/bin/modules C:/hybris/bin/custom
```

소스 안 설정에서는 `INCLUDE_EXTENSIONS = {"myaddon", "mytest", "myextra"};`처럼 씁니다.

- 이름은 루트 아래 `extensioninfo.xml`의 `<extension name>`과 비교합니다. 대소문자를 구분합니다.
- 같은 이름의 폴더가 여러 개 있으면 **모두** 포함하고 경고를 출력합니다.
- 루트 아래에서 찾지 못한 이름은 경고만 출력하고 넘어갑니다. 이미 활성 목록에 있는 이름은 `(already activated)`로 표시합니다.
- 지정한 extension이 `requires-extension`으로 요구하는 다른 extension은 **자동으로 추가되지 않습니다.** 필요하면 그 extension도 함께 지정하세요.
- 로드 순서에서 수동 지정 extension은 보통 `extensions.csv`의 extension보다 뒤에 놓입니다. 그래서 같은 bean id가 있으면 수동 지정한 쪽의 정의가 덮어씁니다. 예외도 있습니다. `localextensions.xml`에 적혀 있거나, 다른 활성 extension이 의존하는 extension이면 앞에 놓일 수 있습니다.
- `--extensions-csv` 없이 지정하면 효과가 없습니다. 이때는 원래 전체가 분석 대상이므로 경고만 출력합니다.
- `scan_complete=false` 등으로 전체 분석으로 돌아가는 경우에도 전체가 대상이므로 지정한 extension은 당연히 포함됩니다.

**효과**
- 비활성 extension의 bean이 registry에 들어가지 않습니다. 그래서 `[MULTI]`/`[UNRESOLVED]` 후보가 줄어듭니다.
- 비활성 extension이 같은 bean id를 덮어써서 엉뚱한 구현으로 연결되는 일이 없어집니다.
- 비활성 extension의 컨트롤러는 분석하지 않습니다.
- 파싱하는 파일이 줄어서 시간과 메모리도 줄어듭니다.

**전체 분석으로 되돌아가는 경우 (필터를 쓰지 않음)**

| 조건 | 동작 |
|---|---|
| `extensions.csv`와 같은 폴더의 `summary.csv`가 `scan_complete=false` | 경고를 출력하고 **전체 extension을 대상**으로 분석합니다. extractor가 일부 `extensioninfo.xml`을 읽지 못해서 활성 목록에 빠진 것이 있을 수 있기 때문입니다. |
| `extensions.csv`를 읽을 수 없음 | 경고 후 전체 분석 |
| `extensions.csv`의 extension 폴더가 루트 아래에 하나도 없음 (`--base` 불일치 등) | 경고 후 전체 분석 |

**경고만 출력하고 필터는 쓰는 경우**
- `summary.csv`가 `selection_complete=false`일 때. 판정 문제가 있다는 뜻이므로 `activation-issues.csv`를 확인하세요.
- `summary.csv`가 없을 때. 스캔이 완전했는지 확인할 수 없습니다.
- `extensions.csv`의 일부 extension이 루트 아래에 없을 때. 해당 개수를 출력합니다.

진행 로그에 `activated extensions: csv=… matched=… of found=…`와 필터 전후 파일 수가 출력됩니다.

**한계**
- extension 단위로만 좁힙니다. 활성 extension 안에 있는 `*-spring.xml`은 실제로 로드되지 않는 파일이라도 모두 읽습니다.
- 활성 여부는 extractor가 정적으로 추정한 결과입니다.

## 처리 순서

1. **파일 수집**: 각 루트에서 `.java`, `extensioninfo.xml`, `*-spring.xml`, `*-servlet.xml`를 모읍니다.
   - 건너뛰는 폴더: `.git`, `.svn`, `node_modules`, `classes`, `eclipsebin`, `testsrc`, `.settings`
   - 이름에 `-`가 들어간 `.java`(예: `package-info.java`)도 건너뜁니다.
   - `--extensions-csv`를 지정하면 여기서 활성 extension 폴더의 파일만 남깁니다(위 「활성 extension만 분석」).
2. **Java 인덱스**: 각 파일의 `package` 선언으로 FQN을 만들어 FQN과 경로, 단순명과 FQN 목록을 대응시킵니다.
3. **extension 순서**: `extensioninfo.xml`의 `requires-extension`을 따라, `localextensions.xml`에 적힌 순서(`--extensions-csv`를 쓰면 그다음 `extensions.csv`의 순서)를 출발점으로 의존 extension을 먼저 배치합니다. 그 뒤에 남은 extension을 알파벳 순서로 붙입니다. Spring XML은 가장 깊은 extension 폴더에 속하는 것으로 봅니다.
   - 웹 XML: 경로에 `/web/webroot/`가 있거나, 파일명이 `-web-spring.xml` 또는 `-servlet.xml`로 끝나는 것
   - 그 밖의 XML은 core XML
4. **core registry**: 모든 extension의 core XML을 위 순서대로 읽습니다. 같은 bean id는 나중 것이 덮어씁니다.
5. **컨트롤러 분석**: `@Controller` 또는 `@RestController`가 붙은 클래스를 accelerator / OCC 계열로 나누고(어디에도 속하지 않으면 건너뜀), 각 클래스마다 다음을 처리합니다. 결과는 계열별 파일에 씁니다.
   - 그 컨트롤러가 속한 extension과 그 의존 extension들의 웹 XML로 **web registry**를 만듭니다. 부모는 core registry입니다.
   - `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`이 붙은 메서드마다 호출 트리를 만듭니다.
   - **CMS 컴포넌트 컨트롤러**: `AbstractAcceleratorCMSComponentController<T extends AbstractCMSComponentModel>`를 상속(간접 상속 포함)한 컨트롤러는, 매핑 어노테이션이 없어도 **그 클래스에 선언된 private가 아닌 메서드 전부**(public / protected / package-private, 예: `fillModel`)를 시작점으로 삼습니다.
     - 상속 여부는 `extends` 절을 따라 올라가면서 단순 클래스명으로 판정합니다. 그래서 상위 클래스 소스가 분석 루트에 없어도(jar로만 제공되는 경우) 인식합니다.
     - private 메서드는 시작점이 되지 않지만, 다른 메서드에서 호출하면 트리 안에 자식 노드로 나옵니다.
     - 상위 클래스에서 상속만 받고 재정의하지 않은 메서드는 대상이 아닙니다.

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

`CallTreeExtractor`는 기본값이 `false`라서 가지치기를 하지 않습니다. 매핑 메서드가 있는 모든 엔드포인트(CMS 컴포넌트 컨트롤러는 private가 아닌 모든 메서드)와, 위 "생략되는 노드"를 뺀 모든 호출 노드를 출력합니다.
`true`로 바꾸면 아래처럼 동작합니다.

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

[CONTROLLER] bin/custom/mystorefront/web/src/.../ProductCarouselComponentController.java  (bean productCarouselComponentController)  (extends AbstractAcceleratorCMSComponentController)
  @ [CMS-COMPONENT] protected fillModel(HttpServletRequest, Model, ProductCarouselComponentModel)
  ProductCarouselComponentController#fillModel(HttpServletRequest, Model, ProductCarouselComponentModel)  [...:60]
     └─ ProductFacade#getProductForCodeAndOptions(String, Collection<ProductOption>)  [...]  {impl=...DefaultProductFacade}  <= @Resource("productFacade") -> bean[productFacade] ...
```

| 표기 | 의미 |
|---|---|
| `[CONTROLLER] 경로  매핑  (bean id)` | 컨트롤러 파일, 클래스 레벨 매핑, 해당 컨트롤러 bean id |
| `@ [HTTP] path` | 엔드포인트 메서드의 매핑 |
| `(extends AbstractAcceleratorCMSComponentController)` | CMS 컴포넌트 컨트롤러로 판정된 컨트롤러 (`[CONTROLLER]` 줄 끝에 표시) |
| `@ [CMS-COMPONENT] 접근제어자 method(타입...)` | CMS 컴포넌트 컨트롤러의 매핑 없는 비 private 메서드. 예: `@ [CMS-COMPONENT] protected fillModel(HttpServletRequest, Model, BannerComponentModel)` |
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
- CMS 컴포넌트 컨트롤러 판정은 상위 클래스의 **단순 클래스명**(`AbstractAcceleratorCMSComponentController`)만 비교합니다. 패키지가 다른 같은 이름의 클래스도 똑같이 판정됩니다.
- CMS 컴포넌트 컨트롤러는 실제로는 상위 클래스의 요청 처리 메서드가 `fillModel` 등을 호출하는 구조입니다. 이 도구는 하위 컨트롤러의 메서드를 바로 시작점으로 삼기 때문에, 상위 클래스 쪽 처리는 트리에 나오지 않습니다.
