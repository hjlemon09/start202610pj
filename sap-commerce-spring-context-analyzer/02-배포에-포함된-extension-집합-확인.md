> SAP Commerce 2211 지정 테넌트의 코어 Spring XML 조사 절차서 — 2/13장. 이전: [1. 지정 테넌트 ID와 대상 배포 확인](01-지정-테넌트-ID와-대상-배포-확인.md) · 다음: [3. 지정 테넌트의 활성 extension 집합 확인](03-지정-테넌트의-활성-extension-집합-확인.md)

# 2. 배포에 포함된 extension 집합 확인

작업 목적. 설치 디렉터리에 있는 모든 extension 중 대상 빌드가 실제로 선택한 집합을 구한다.

입력. 1장에서 확정한 설치와 적용 설정, 대상 빌드 자료.

출력. extension 이름, 실제 디렉터리, 포함 근거, 선행 의존성.

## 2.1 `localextensions.xml`에서 명시 선언과 검색 경로를 읽는다

### 이 절의 목적

이 문서의 최종 목표는 정적 분석만으로, 대상 테넌트에서 최종 로드될 것으로 예상되는 Spring 빈을 가능한 범위까지 목록으로 만드는 것이다. 코어 Spring 빈은 각 extension의 Spring XML에 정의된다. 따라서 **어떤 extension이 대상 빌드에 포함되는지가 빈 분석의 범위를 결정한다.**

- 포함된 extension을 빠뜨리면, 그 extension이 정의하는 빈 전체가 목록에서 누락된다.
- 포함되지 않은 extension을 넣으면, 실제로는 로드되지 않을 빈이 목록에 들어간다.

`localextensions.xml`은 프로젝트가 직접 선언한 포함 목록이며, extension 집합을 구하는 첫 번째 입력이다. 이 절에서는 이 파일만 읽어 **명시적으로 포함된 extension**과 **extension을 찾을 검색 경로**를 확정한다.

이 절의 결과는 아직 최종 extension 목록이 아니다. 플랫폼이 자동으로 포함하는 extension과 `requires-extension`으로 끌려 들어오는 extension은 **2.2절**에서 추가한다.

**배경지식: `requires-extension`**

**extension 의존성이란.** 어떤 extension이 다른 extension에 정의된 타입이나 기능을 사용·수정·확장하면, 그 extension은 다른 extension에 **의존**한다. 의존하는 extension은 필요한 extension이 있어야 정상 동작한다. 예를 들어 CronJob을 확장하면 `processing` extension에, Product 타입만 써도 플랫폼 `core` extension에 의존한다.

**`requires-extension`은 이 의존성을 명시하는 선언이다.** `localextensions.xml`이 아니라 각 extension 폴더의 `extensioninfo.xml`에서, `<extension>` 요소의 자식으로 적는다. 이름만 적고 경로는 적지 않는다.

```xml
<!-- myextension/extensioninfo.xml (SAP Help의 예) -->
<extension name="myextension">
  <requires-extension name="voucher"/>
  <requires-extension name="cms"/>
</extension>
```

**이 선언이 하는 일**

|하는 일|뜻|이 문서에서 다루는 곳|
|---|---|---|
|필요한 extension을 자동으로 포함한다.|`localextensions.xml`에 `myextension`만 적어도 `voucher`와 `cms`가 함께 로드된다. 필요한 extension은 따로 적지 않아도 된다.|2.2절|
|필요한 extension을 `<path>`에서 찾는다.|이름만 있으므로 `localextensions.xml`의 `<path>`(lookup folder) 아래에서 폴더를 찾는다. 같은 이름이 여러 곳에 있으면 먼저 적힌 `<path>`의 위치가 우선한다.<br>근거: SAP Help [Configuration Options in localextensions.xml (2205)](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US) 「Loading Extensions by Name」의 Note ("…it is loaded from a location your first provided path points to"). 이 문서는 **같은 `<path>` 아래**의 중복은 다루지 않는다.|2.1.3절(검색 범위), 2.2절|
|연쇄된다.|`voucher`도 자기 `extensioninfo.xml`에 `requires-extension`을 가질 수 있다. 새 이름이 나오지 않을 때까지 따라간다.|2.2절|
|순서를 보장한다.|`myextension`은 `voucher`와 `cms`가 모두 빌드된 뒤에 빌드된다. 의존 관계가 선언되지 **않은** extension끼리의 빌드 순서와 초기화 순서는 정해져 있지 않다(non-deterministic).|7장|
|필요한 extension의 코어 소스를 쓸 수 있게 한다.|의존하는 extension은 필요한 extension의 `src`(coremodule) 클래스와 리소스를 사용할 수 있다. `web/src`(webmodule)의 클래스는 사용할 수 없다.|6장(클래스패스)|

**예외 1: 플랫폼 extension에 대한 의존은 적지 않아도 된다.** `<HYBRIS_BIN_DIR>/platform/ext` 밖에 있는 extension은 모두 플랫폼 extension에 의존한다고 간주된다. 그래서 `core` 같은 플랫폼 extension은 `requires-extension`으로 적지 않는 경우가 많다. 2.2절에서 의존성을 따라갈 때, 플랫폼 extension이 선언에 없다고 의존 관계가 없다고 판단하지 않는다.

**예외 2: `load-prior-to`는 순서만 정하고 포함시키지는 않는다.** 의존 관계 없이 로드 순서만 정하고 싶을 때는 먼저 로드될 extension의 `extensioninfo.xml`에 `<meta key="load-prior-to" value="extA"/>`를 적는다. `requires-extension`과 방향이 반대다.

|선언|적는 곳|A를 B보다 먼저 로드하려면|자동 포함|
|---|---|---|---|
|`requires-extension`|B의 `extensioninfo.xml`|B가 `<requires-extension name="A"/>`를 적는다.|B가 포함되면 A도 자동으로 포함된다. A를 `localextensions.xml`에 따로 적지 않아도 된다.|
|`load-prior-to`|A의 `extensioninfo.xml`|A가 `<meta key="load-prior-to" value="B"/>`를 적는다.|어느 쪽도 자동으로 포함하지 **않는다**. B가 `localextensions.xml`에 없으면 B는 로드되지 않고, 순서 지정도 적용되지 않는다.|

**빈 목록과의 관계**

- **누락 방지:** 의존성으로 포함된 extension의 Spring XML도 로드된다. `localextensions.xml`에 이름이 없다고 이 extension들을 빠뜨리면, 그 빈이 목록에서 누락된다.
- **덮어쓰기 판단:** 여러 extension이 같은 빈 이름(또는 alias)을 정의하면, **나중에 로드된 extension의 정의가 앞의 정의를 덮어쓴다.** SAP Help는 의존 관계가 없는 두 extension이 같은 alias `myBean`을 정의하면, 로드 순서가 정해져 있지 않아 기대와 다른 빈이 최종 값이 될 수 있다는 예를 든다. 따라서 어떤 빈이 최종으로 남는지는 `requires-extension`과 `load-prior-to`로 정해지는 로드 순서로 판단하고, 둘 다 없으면 순서 미확정으로 기록한다(7장).

**예외 1의 전제.** 플랫폼 extension에 대한 위 자동 처리는 `localextensions.xml`의 **`<extensions>` 요소의 `autoload`**로 켜고 끈다. 생략하면 `true`이고, `false`이면 필요한 플랫폼 extension을 `localextensions.xml`에 직접 적어야 한다. 이것은 2.1.3절에서 다루는 `<path>`의 `autoload`(기본값 `false`)와 이름만 같은 다른 설정이다. 설치된 `extensions.xsd`에서 `extensionsType`의 `autoload`가 `use="optional" default="true"`로 정의되어 있고, 설명에 "Loads automatically all extensions of the platform"이라고 적혀 있는 것으로 확인했다.

근거:
- SAP Help [Extension Dependencies (SAP Commerce Cloud, 2211)](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/20125f0eca6340dba918bda360e3cdfa/8bbf3a9d86691014aa4189bf3ac0eb88.html?locale=en-US): 의존성의 정의, `requires-extension` 선언 형식, 빌드 순서 보장과 비결정적 순서, coremodule 접근, 플랫폼 extension 예외, `load-prior-to`, 같은 alias의 덮어쓰기 예.
- SAP Help [Configuration Options in localextensions.xml (2205)](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US)의 「Defining Lookup Folder for Dependent Extensions」, 「Loading Extensions by Name」: lookup folder에서 찾는 규칙.

|이 절의 결과|사용하는 곳|
|---|---|
|판독한 `localextensions.xml`의 절대 경로|이 절의 모든 판독의 기준.|
|검색 경로 목록(`<path>`)|2.1.4절과 2.2절에서 extension 이름을 실제 폴더로 찾을 때의 탐색 범위.|
|명시 포함 extension 목록(정식 이름·폴더·근거)|2.2절 의존성 해석의 시작 집합. 3장 활성 판정, 4장 `<extension>.application-context` 조회, 6장 Spring XML 위치 확인의 대상 이름과 폴더.|
|미확정 항목|2.3절에서 해소하거나, 빈 목록의 확정 범위를 제한하는 근거.|

**파일 구조 예시**

아래는 설명용 예시다. 실제 조사는 2.1.1절에서 정한 파일의 원문으로 한다.

```xml
<hybrisconfig>
  <extensions>
    <path dir="${HYBRIS_BIN_DIR}" autoload="false"/>
    <path dir="${HYBRIS_BIN_DIR}/custom/autoloaded" autoload="true"/>

    <extension name="backoffice"/>
    <extension dir="${HYBRIS_BIN_DIR}/custom/melco/melcocore"/>
    <!-- <extension name="oldextension"/> -->
  </extensions>
</hybrisconfig>
```

|선언|뜻|이 절에서 정할 것|
|---|---|---|
|`<extensions autoload="…">`|`<path>`·`<extension>`을 감싸는 요소. `autoload`는 **플랫폼 extension(`platform/ext`)을 자동으로 모두 로드할지**를 정한다. 생략하면 `true`다. `false`이면 플랫폼 extension도 이 파일에 직접 적어야 한다.|원문 값 또는 `생략`과 적용값 (2.1.2절에서 기록)|
|`<path dir="…" autoload="…" depth="…"/>`|extension을 찾아볼 **검색 경로**. `autoload`가 참이면 그 경로에서 발견한 extension을 선언 없이 포함한다. 거짓(생략 시 기본값)이면 lookup folder로만 쓰이며, 그 경로의 extension은 `<extension>`으로 명시했거나 `requires-extension`으로 요구될 때만 포함된다.|실제 경로, 자동 포함 여부, 탐색 깊이 (2.1.3절)|
|`<extension name="…"/>`|포함할 extension을 **이름으로** 지정한다.|그 이름을 가진 실제 폴더 (2.1.4절)|
|`<extension dir="…"/>`|포함할 extension을 **폴더로** 지정한다.|그 폴더의 정식 extension 이름 (2.1.5절)|
|`<webapp …/>`|외부 extension(웹 애플리케이션)을 추가하는 선언이다.|원문만 기록한다. 코어 컨텍스트와의 관계는 필요할 때 따로 확인한다.|
|`<!-- … -->` 안의 선언|주석이므로 빌드가 읽지 않는다.|분석 대상에서 제외 (2.1.2절)|

`autoload`라는 이름의 속성이 두 곳에 있으므로 구분한다. 근거는 설치된 스키마 `<HYBRIS_HOME>\bin\platform\resources\schemas\extensions.xsd`다.

|속성|정의 위치(스키마)|뜻|생략했을 때|
|---|---|---|---|
|`<extensions autoload>`|`extensionsType`|플랫폼 extension 전체를 자동으로 로드할지|`true`|
|`<path autoload>`|`scanType`|그 경로에서 발견한 extension을 자동으로 포함할지|`false`|

### 2.1.1 판독할 `localextensions.xml`을 정한다

**무엇을 확인하는가**

1.1절에서 확정한 `CONFIG_DIR`에 `localextensions.xml`이 있는지 확인한다.

**왜 확인하는가**

이 절의 모든 판독 대상을 정하는 단계다.

**어떻게 확인하는가**

1. 1.1절 기록표의 `localextensions.xml` 절대 경로를 연다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.1.1-A|1.1절에서 확정한 `CONFIG_DIR`에 `localextensions.xml`이 있다.|이 파일을 **판독 대상으로 확정**한다.|2.1.2절로 이동한다.|
|2.1.1-B|파일이 없다.|기본 입력이 **없음**으로 기록한다.|1.1.3절의 「파일이 없을 때」 절차를 따른다. 대체 입력을 찾으면 그 파일로 2.1.2절을 진행한다.|

### 2.1.2 주석 처리된 선언을 제외하고 유효한 선언만 고른다

**무엇을 확인하는가**

`<!-- … -->` 주석 안에 있는 `<extension>`과 `<path>`, 그리고 파일이 XML로 올바르게 읽히는지 확인한다.

**왜 확인하는가**

주석 처리된 extension은 빌드에 포함되지 않는다. SAP Help도 선언을 XML 주석(`<!-- -->`)으로 감싸고 다시 빌드하면 그 extension이 빠진다고 설명한다([근거: Configuration Options in localextensions.xml](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US)의 「Specifying Required Extensions One by One」). 문자열 검색으로 `extension name=`을 세면 주석 안의 선언까지 포함되어, 로드되지 않을 extension의 빈이 목록에 들어간다.

**어떻게 확인하는가**

1. IntelliJ에서 파일을 XML로 연다. 주석 구간은 회색으로 표시되므로 주석 안의 선언을 구별할 수 있다.
2. 여러 줄에 걸친 주석은 시작(`<!--`)과 끝(`-->`)의 위치를 확인해 그 사이의 선언을 모두 주석으로 분류한다.
3. IntelliJ에 XML 오류 표시가 있으면 오류 위치를 기록한다. 파일이 XML로 읽히지 않으면 빌드도 이 파일을 정상적으로 읽지 못한다.
4. `<extensions>` 요소의 `autoload` 원문을 기록한다. 속성이 없으면 `생략`으로 적고 적용값은 `true`로 기록한다. 이 값은 2.2절에서 플랫폼 extension을 자동 포함할지 판단하는 근거다.
5. 유효한 선언마다 요소 종류(`path`·`extension name`·`extension dir`·`webapp`), 원문, 줄 번호를 기록한다. 특히 `<path>`는 **선언 순서**를 기록한다. 같은 이름의 extension이 여러 경로에 있으면 먼저 적힌 `<path>`의 위치에서 로드되기 때문이다(2.1.4절 참조).

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.1.2-A|주석 밖에 있는 선언이다.|**유효 선언**으로 기록한다.|`<path>`는 2.1.3절, `<extension name>`은 2.1.4절, `<extension dir>`은 2.1.5절로 넘긴다.|
|2.1.2-B|주석 안에 있는 선언이다.|**제외 선언**으로 기록한다. 이 선언만으로는 extension을 포함하지 않는다.|분석 대상에서 뺀다. 같은 extension이 2.2절 의존성으로 다시 포함될 수 있으므로 이름은 기록해 둔다.|
|2.1.2-C|XML 오류가 있다.|파일 판독을 **미확정**으로 둔다.|오류 위치를 기록한다. 1.1절에서 확정한 경로의 파일이 맞는지 다시 확인하고, 해결하지 못하면 2.1절 결과 전체를 미확정으로 둔다.|
|2.1.2-D|같은 extension을 `<extension>`으로 두 번 이상 선언했다. 같은 이름 반복, 같은 폴더 반복, `name`과 `dir`이 서로 다른 폴더를 가리키는 경우를 모두 포함한다.|**중복 선언**으로 기록하고 **미확정**으로 둔다. SAP 문서에 이때의 동작이 없으므로 자동으로 판정하지 않는다.|두 선언의 원문과 줄 번호를 기록한다. 빌드 로그의 `[echo] … path:` 줄로 실제 사용된 폴더를 확인한 뒤 경우별로 판정한다.|

### 2.1.3 `<path>` 검색 경로와 자동 포함 조건을 해석한다

**배경지식**

`<path>`는 **빌드가 extension 폴더를 찾아볼 위치**를 지정하는 선언이다. 세 속성은 다음 뜻을 가진다.

|속성|뜻|생략했을 때|
|---|---|---|
|`dir`|extension을 찾아볼 폴더. 예: `${HYBRIS_BIN_DIR}/custom`|생략할 수 없다.|
|`autoload`|그 폴더에서 발견한 extension을 선언 없이 자동으로 포함할지 여부|`false`. 자동 포함하지 않는다.|
|`depth`|그 폴더 아래 몇 단계 하위 폴더까지 찾아볼지|10단계|

- `autoload="true"`가 아닌 `<path>`는 **lookup folder**다. 그 아래의 extension은 `<extension>`으로 명시했거나, 명시한 extension의 의존성(`requires-extension`)을 채우는 데 필요할 때만 로드된다.
- `<HYBRIS_BIN_DIR>/platform/ext` 아래의 extension은 `<path>` 선언과 관계없이 모두 자동으로 로드된다. 단, `<extensions>` 요소의 `autoload`가 `true`(생략 시 기본값)일 때다. 이 extension들은 2.2절에서 다룬다.
- 같은 extension이 여러 `<path>` 아래에서 발견되면, **먼저 적힌 `<path>`가 가리키는 위치**에서 로드된다.
- `<path>`를 먼저 적고, 그 뒤에 `<extension>`을 적는다.

근거: SAP Help [Configuration Options in localextensions.xml (2205)](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US). `depth`의 기본값은 이 문서의 Note에 있는 "The default value is ten levels."에 따른다. [2011판](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US&version=2011)에도 같은 설명이 있다. `autoload`의 기본값 `false`는 설치된 스키마(`extensions.xsd`의 `scanType`)의 `default="false"`와 일치한다.
**무엇을 확인하는가**

유효한 `<path>`마다 세 속성의 원문과, `dir`의 변수를 치환한 실제 절대 경로를 확인한다.

**왜 확인하는가**

두 가지를 확정하기 위해서다.

1. **이름으로 폴더를 찾는 범위를 확정하기 위해서.** `<extension name="melcocore"/>`나 2.2절의 `<requires-extension name="…"/>`에는 이름만 있고 폴더 위치가 없다. 빌드는 `<path>`가 가리키는 폴더 아래에서 `extensioninfo.xml`의 이름이 일치하는 폴더를 찾아 사용한다. 이 범위를 모르면, 검색 경로 밖에 있는 같은 이름의 복사본을 잘못 골라 실제로 로드되지 않는 Spring XML을 분석하게 된다. → 2.1.4절과 2.2절에서 이 범위를 사용한다.
2. **선언 없이 자동으로 포함되는 extension을 알기 위해서.** `autoload`가 참인 경로의 extension은 `<extension>` 선언이 없어도 빌드에 포함된다. 이를 놓치면 그 extension들의 빈이 목록에서 통째로 빠진다. → 2.1.5절에서 자동 포함 후보의 정식 이름을 확인한다.

**어떻게 확인하는가**

1. `dir`의 변수를 치환한다. `${HYBRIS_BIN_DIR}`은 표준 구성에서 `<HYBRIS_HOME>\bin`이다. 다른 변수가 있으면 대상 버전의 플랫폼 빌드 코드에서 그 변수를 정의하는 곳을 찾아 값과 출처를 확인한다.
2. 치환한 경로가 실제로 존재하는지 확인한다.
3. `autoload`와 `depth`는 원문을 기록하고, 속성이 없으면 `생략`으로 적는다.
4. 생략된 속성에는 배경지식 표의 기본값을 적용한다. `autoload`는 `false`, `depth`는 10단계다. 적용값과 함께 근거(SAP Help 문서 또는 스키마)를 기록한다.

   설치된 스키마로 `autoload`의 기본값을 직접 확인하려면 다음 순서로 본다.
   1. `localextensions.xml` 맨 위 `<hybrisconfig>`의 `xsi:noNamespaceSchemaLocation`이 가리키는 스키마를 연다. 표준 설치에서는 `<HYBRIS_HOME>\bin\platform\resources\schemas\extensions.xsd`다.
   2. `<xs:element name="path" type="…">`의 `type` 값(표준 설치에서는 `scanType`)을 확인하고, 같은 파일의 `<xs:complexType name="scanType">`을 찾는다.
   3. 그 안의 `<xs:attribute name="autoload">`와 `<xs:attribute name="depth">`를 읽는다. `default="…"`가 있으면 그 값이 생략 시 적용값이다.

   **참고(2211 설치에서 확인한 값):** `scanType`에는 `autoload`가 `use="optional" default="false"`, `depth`가 `use="optional"`(`default` 없음)로 정의되어 있다. 스키마에는 `depth`의 기본값이 없으므로, `depth`를 생략하면 SAP Help 문서에 따라 10단계를 적용한다.
5. `depth`의 적용값으로 경로 아래 몇 단계 폴더까지 `extensioninfo.xml`을 찾는지 정한다. 예를 들어 `depth`가 10이면 `dir` 아래 10단계 하위 폴더까지 찾는다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.1.3-A|경로가 해석되었고 `autoload`가 거짓이다.|**탐색 전용 경로**로 확정한다. 이 경로의 extension은 선언되거나 요구될 때만 포함된다.|2.1.4절과 2.2절의 이름 탐색 범위로 사용한다.|
|2.1.3-B|경로가 해석되었고 `autoload`가 참이다.|**자동 포함 경로**로 확정한다.|`depth` 범위 안의 모든 `extensioninfo.xml`을 찾아 2.1.5절에서 정식 이름을 확인한다. 각 extension의 포함 근거는 `autoload 경로`로 기록한다.|
|2.1.3-C|`autoload` 또는 `depth`가 생략됐고 대상 버전의 적용값을 확인했다.|적용값에 따라 2.1.3-A 또는 2.1.3-B로 판정한다.|적용값과 근거(SAP Help 문서 또는 스키마 파일의 `default`)를 함께 기록한다.|
|2.1.3-D|생략된 속성의 적용값이나 `dir`의 변수값을 확인하지 못했다.|해당 경로를 **미확정**으로 둔다.|이 경로에서 찾는 이름과 자동 포함 후보는 모두 `포함 미확정`으로 표시하고, 부족한 값과 확인할 문서·코드를 기록한다.|
|2.1.3-E|해석한 경로가 존재하지 않는다.|**존재하지 않는 경로**로 기록한다. 이 경로로는 extension을 찾을 수 없다.|변수값이 맞는지 다시 확인한다. 값이 맞다면 이 경로는 탐색 대상에서 제외한다.|

### 2.1.4 `<extension name>` 선언을 실제 폴더에 연결한다

**무엇을 확인하는가**

이름으로 선언한 extension마다, 2.1.3절의 검색 경로 안에서 `extensioninfo.xml`의 `<extension name="…">` 값이 그 이름과 일치하는 폴더를 찾는다.

**왜 확인하는가**

extension의 Spring XML과 프로퍼티 파일은 그 extension 폴더 안에 있다. 폴더가 정해져야 4장에서 `<extension>.application-context` 값을, 6장에서 실제 Spring XML 파일을 확인할 수 있다. 폴더 이름과 extension 이름은 다를 수 있으므로 폴더 이름만 보고 연결하지 않는다.

**어떻게 확인하는가**

1. IntelliJ의 Find in Files에서 파일 마스크를 `extensioninfo.xml`로 지정하고, `name="<선언한 이름>"`을 검색한다. 검색 범위는 2.1.3절에서 확정한 검색 경로들이다.
2. 찾은 파일이 검색 경로의 `depth` 범위 안에 있는지 확인한다. 범위 밖에 있는 파일은 대상 빌드가 찾지 않는다.
3. 찾은 파일의 `<extension>` 요소의 `name` 값이 선언한 이름과 정확히 같은지 확인한다. 다른 요소의 `name` 속성이 검색된 것은 제외한다.
4. 일치하는 파일의 절대 경로와, 그 파일이 있는 폴더를 기록한다.
5. 일치하는 폴더가 둘 이상이면 각 폴더가 어느 `<path>` 아래에 있는지 기록한다. 같은 extension이 여러 `<path>` 아래에서 발견되면 **먼저 적힌 `<path>`가 가리키는 위치**에서 로드된다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.1.4-A|일치하는 폴더가 하나다.|**명시 포함**으로 확정한다. 포함 근거는 `name 선언`과 선언 줄 번호다.|결과 목록에 추가한다.|
|2.1.4-B|일치하는 폴더가 둘 이상이고, 서로 다른 `<path>` 아래에 있다.|**먼저 적힌 `<path>` 아래의 폴더**로 확정한다. 나머지 폴더는 `중복·미사용`으로 기록한다.|결과 목록에 선택한 폴더로 추가한다. 선택 근거로 두 `<path>`의 선언 줄 번호를 기록한다.|
|2.1.4-C|일치하는 폴더가 둘 이상이고, 같은 `<path>` 아래에 있다.|폴더를 **후보 여러 개**로 기록한다. 포함 자체는 확정, 폴더는 미확정이다.|문서의 규칙으로는 하나를 고를 수 없다. 2.3절에서 같은 경로 안의 탐색 순서를 확인한다.|
|2.1.4-D|검색 경로에서 찾지 못했지만 `<HYBRIS_HOME>\bin\platform\ext`에 있다.|**명시 포함**으로 확정하되 폴더는 플랫폼 영역으로 기록한다. `<extensions autoload>`가 `true`(생략 시 기본값)이면 `platform/ext` 아래 extension은 선언과 관계없이 자동으로 로드된다([근거](https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US)). `false`이면 이 선언이 있어야 포함된다.|2.2절의 플랫폼 자동 포함 extension과 겹치는지 확인한다.|
|2.1.4-E|어디에서도 찾지 못했다.|**누락 선언**으로 기록한다.|검색한 경로와 문자열을 기록한다. 빌드가 이 선언을 오류로 처리하는지 또는 무시하는지 2.3절에서 확인한다. 이 extension의 빈은 목록에 넣지 않는다.|

### 2.1.5 `<extension dir>` 선언과 자동 포함 후보의 정식 이름을 확인한다

**무엇을 확인하는가**

폴더로 선언한 extension과 2.1.3-B의 자동 포함 후보마다, 그 폴더 바로 아래 `extensioninfo.xml`의 `<extension name="…">` 값을 확인한다.

**왜 확인하는가**

이후 장은 모두 **정식 extension 이름**을 기준으로 한다. 3장의 테넌트 활성 판정, 4장의 `<extension>.application-context` 프로퍼티 키, 7장의 의존 순서가 모두 이 이름을 쓴다. 폴더 이름으로 기록하면 이후 장에서 프로퍼티 키와 의존 관계를 잘못 찾는다.

**어떻게 확인하는가**

1. `dir`의 변수를 2.1.3절과 같은 방법으로 치환해 절대 경로를 구한다. 상대 경로라면 대상 버전이 무엇을 기준으로 해석하는지 확인한다.
2. 그 폴더 바로 아래의 `extensioninfo.xml`을 연다.
3. `<extension>` 요소의 `name` 값을 정식 이름으로 기록한다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.1.5-A|`extensioninfo.xml`이 있고 `name`을 읽었다.|**포함**으로 확정한다. 포함 근거는 `dir 선언` 또는 `autoload 경로`다.|결과 목록에 정식 이름으로 추가한다.|
|2.1.5-B|폴더는 있지만 바로 아래에 `extensioninfo.xml`이 없다.|이 폴더는 **extension이 아님**으로 기록한다.|`dir` 선언이라면 선언 원문과 변수 치환을 다시 확인한다. autoload 후보라면 제외한다.|
|2.1.5-C|`dir`의 변수나 상대 경로 기준을 확인하지 못했다.|폴더를 **미확정**으로 둔다.|추측한 경로로 이름을 정하지 않는다. 부족한 변수값과 출처를 기록한다.|
|2.1.5-D|정식 이름이 다른 선언이나 다른 폴더의 이름과 같다.|**중복 이름**으로 기록한다.|서로 다른 `<path>` 아래에 있으면 2.1.4-B와 같이 먼저 적힌 `<path>` 아래의 폴더를 선택한다. 같은 `<path>` 아래에 있으면 2.1.4-C와 같이 2.3절에서 확인한다.|

### 2.1.6 조사 결과를 2.2절의 입력으로 정리한다

**검색 경로 기록**

|선언 순서|`dir` 원문|실제 절대 경로|`autoload` 원문 / 적용값|`depth` 원문 / 적용값|판정 번호|
|---|---|---|---|---|---|
| | | | | | |

`autoload`나 `depth`를 생략했다면 원문에 `생략`, 적용값에 기본값(`false`, 10)을 적는다.

**명시 포함 extension 기록**

|정식 이름|포함 근거|선언 위치(파일:줄)|`extensioninfo.xml` 절대 경로|판정 번호|상태|
|---|---|---|---|---|---|
| |`name 선언` / `dir 선언` / `autoload 경로`| | | |확정 / 후보 여러 개 / 미확정 / 누락|

같은 이름의 폴더가 여러 개였던 extension은, 선택하지 않은 폴더를 아래 「제외 선언과 미확정 항목」에 `중복·미사용`으로 기록한다.

**제외 선언과 미확정 항목**

|항목|원문 또는 이름|이유|확인할 곳|
|---|---|---|---|
| | | | |

### 2.1절 완료 기준

- 판독할 `localextensions.xml`을 정했다.
- 주석 처리된 선언을 모두 제외하고 유효 선언만 판독했다.
- 모든 `<path>`의 실제 경로, 자동 포함 여부, 탐색 깊이를 정했거나 미확정 사유를 기록했다.
- 모든 유효 `<extension>` 선언과 자동 포함 후보에 정식 이름과 폴더를 연결했거나, `후보 여러 개`·`누락`·`미확정` 상태와 다음 확인 위치를 기록했다.

이 기준을 충족하면 **2.2절**로 이동해 플랫폼 자동 포함 extension과 `requires-extension` 의존성을 추가한다.

## 2.2 플랫폼 자동 로드와 의존성을 반영한다

1. 대상 버전에서 자동으로 검사하는 플랫폼 핵심 extension 디렉터리를 확인한다.
2. HYBRIS_HOME/bin/platform/ext는 표준 설치의 확인 출발점이다.
3. 명시 목록과 자동 로드 대상의 각 extensioninfo.xml에서 <requires-extension name="…">을 읽는다.
4. 아직 목록에 없는 의존 이름마다 2.1의 탐색 규칙을 적용해 후보를 찾는다.
5. 새로 찾은 각 extension의 의존성을 다시 읽는다.
6. 새 이름이 추가되지 않을 때까지 같은 절차를 반복한다.
7. edge는 필요한 extension → 이를 요구하는 extension 방향으로 기록한다.
8. 소스 경로가 플랫폼 내장, 모듈, 커스텀 중 어디인지 구별하되 선택 결과는 실제 코드와 배포 자료로 판정한다.

## 2.3 중복 이름과 누락을 처리한다

### 이 절의 목적

2.1~2.2절에서 extension 이름을 실제 폴더에 연결하다 보면, 규칙만으로는 하나로 정할 수 없는 경우가 남는다. 이 절은 그 세 가지 경우를 처리한다.

|경우|빈 목록에 주는 영향|다루는 곳|
|---|---|---|
|같은 이름의 extension 폴더가 여러 개|어느 폴더의 Spring XML을 분석할지 달라진다.|2.3.1|
|요구된 extension의 폴더가 없음|그 extension의 빈이 빠지거나, 빌드 자체가 실패하는 구성일 수 있다.|2.3.2|
|extension 사이의 순환 의존|선후 관계를 정할 수 없어 7장의 순서 판정에 영향을 준다.|2.3.3|

세 경우 모두 **근거 없이 하나를 고르지 않는다.** 확인하지 못한 항목은 미확정으로 남기고, 영향을 받는 extension과 빈의 범위를 기록한다.

### 2.3.1 같은 이름의 extension이 여러 폴더에 있을 때

**무엇을 확인하는가**

같은 `<extension name="…">`을 가진 `extensioninfo.xml`이 둘 이상일 때, 대상 빌드가 어느 폴더를 사용하는지 확인한다.

**어떻게 확인하는가**

1. 후보가 된 `extensioninfo.xml`의 절대 경로를 모두 기록한다.
2. 각 후보가 어느 `<path>` 아래에 있는지, 그 `<path>`의 선언 순서를 기록한다(2.1.2절, 2.1.6절의 기록표).
3. 아래 판정표로 선택된 폴더를 정한다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.3.1-A|후보들이 서로 다른 `<path>` 아래에 있다.|**먼저 적힌 `<path>` 아래의 폴더**로 확정한다(2.1.4-B).|나머지 후보는 `중복·미사용`으로 기록한다.|
|2.3.1-B|후보 중 하나만 `<path>` 안에 있고 나머지는 밖에 있다(백업 복사본 등).|`<path>` 안의 폴더로 확정한다. 밖의 후보는 검색되지 않는다.|밖의 후보는 `검색 범위 밖`으로 기록한다.|
|2.3.1-C|후보들이 **같은 `<path>` 아래**에 있다(2.1.4-C).|문서 규칙으로 정할 수 없다. **미확정**으로 둔다.|아래 「2.3.1-C의 확인 방법」을 수행한다.|

**2.3.1-C의 확인 방법**

아래 순서로 확인하고, 앞의 방법으로 확정되면 뒤의 방법은 생략한다.

1. **빌드 로그:** 대상 빌드의 콘솔 출력에서 해당 extension의 `[echo] <이름> … path:…` 줄을 찾는다. `path:` 값이 실제로 사용된 폴더다.
2. **플랫폼 코드:** 빌드 로그가 없으면 bootstrap 코드(예: `PlatformConfig` 등 extension 목록을 만드는 클래스)에서 같은 이름이 충돌할 때 실행되는 분기를 확인한다. 폴더를 나열한 뒤 **정렬하는지**도 확인한다. 정렬하면 순서가 확정되고, 정렬하지 않으면 OS가 돌려주는 순서에 따르므로 정적으로는 확정할 수 없다.
3. 둘 다 확인하지 못하면 후보 경로를 모두 남기고 미확정으로 둔다. 그 extension에서 나오는 빈은 `폴더 미확정`으로 표시한다.

### 2.3.2 요구된 extension을 찾지 못했을 때

**무엇을 확인하는가**

`localextensions.xml`의 `<extension>` 선언이나 `requires-extension`이 가리키는 이름을, 검색 경로에서 찾지 못한 경우다.

**어떻게 확인하는가**

1. 찾지 못한 이름과, 그 이름을 요구한 쪽의 파일과 줄을 기록한다(`localextensions.xml` 또는 요구한 extension의 `extensioninfo.xml`).
2. 검색한 `<path>`와 `depth` 범위를 다시 확인한다. 범위 밖에 같은 이름의 폴더가 있는지도 찾아본다.
3. 대상 빌드 로그나 빌드 결과에 그 이름이 있는지 확인한다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.3.2-A|검색 범위 밖에 같은 이름의 폴더가 있다.|검색 경로 설정의 문제로 기록한다. 대상 빌드도 이 폴더를 찾지 못한다.|빌드 로그에 그 이름이 있으면 2.1.3절의 검색 경로 해석을 다시 확인한다.|
|2.3.2-B|어디에도 없고, 빌드 로그에도 없다.|**누락**으로 기록한다. 이 extension의 빈은 목록에 넣지 않는다.|요구한 쪽이 정상적으로 빌드되는 구성인지(빌드 오류가 되는지) 구현이나 문서로 확인한다.|
|2.3.2-C|어디에도 없지만 빌드 로그에는 있다.|정적 분석 범위 밖에서 제공되는 extension이다. **미확정**으로 둔다.|빌드 로그의 `path:`로 위치를 확인하고, 분석 범위(설치 루트)에 포함되는지 검토한다.|

### 2.3.3 순환 의존이 있을 때

**무엇을 확인하는가**

`requires-extension`을 따라가다가 A → B → … → A처럼 다시 자기 자신으로 돌아오는 관계다.

**어떻게 확인하는가**

1. 순환을 이루는 extension 이름과 각 의존 관계(edge)를 기록한다.
2. 대상 버전의 빌드가 순환을 오류로 처리하는지, 허용하는지 구현이나 문서로 확인한다.

**판정**

|번호|확인 결과|판정|다음 작업|
|---|---|---|---|
|2.3.3-A|순환이 있지만 대상 빌드가 성공한다.|포함 여부는 확정한다(순환 안의 extension은 모두 포함). **선후 관계는 미확정**으로 둔다.|7장에서 순서를 판정할 때 이 extension들을 순서 미확정 그룹으로 다룬다.|
|2.3.3-B|대상 빌드가 순환을 오류로 처리한다.|구성 오류로 기록한다.|해당 구성이 실제 대상 배포인지 1장으로 돌아가 다시 확인한다.|

### 이 절에서 지킬 원칙

- 같은 이름의 여러 후보를 임의로 합치거나 하나를 골라 의존 그래프를 만들지 않는다.
- 확정하지 못한 항목은 후보와 확인할 위치를 남기고, 영향을 받는 extension과 빈의 범위를 함께 기록한다.

## 2.4 빌드 완성 목록과 대조한다

1. 대상 배포를 생성한 빌드 목록, 패키지 인벤토리 또는 적용된 extension 설정이 있으면 읽는다.
2. 정적으로 재구성한 이름 집합과 산출물에 기록된 이름 집합의 차이를 낸다.
3. 차이가 생긴 이름마다 누락된 탐색 경로, 자동 로드 정책, 배포 생성 단계 중 무엇 때문인지 찾는다.
4. 빌드 자료가 경로까지 제공하면 extensioninfo.xml 경로와 대조한다.
5. 대상 자료가 목록의 순서를 나타내는지 여부는 7장에서 따로 판단한다.

완료 기준. 대상 배포의 포함 extension마다 이름, 실제 경로, 포함 이유가 있고, 각 requires-extension edge의 대상이 해석되었다.
중복 후보나 누락이 남아 있다면 그 이름과 조건을 명시해 3장의 확정 범위를 제한한다.

SAP Help — Configuration Options in localextensions.xml (2205)
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US
2.1절의 `depth` 기본값(10단계), `autoload`가 아닌 경로의 lookup folder 동작, `platform/ext` 자동 로드, 같은 이름일 때 먼저 적힌 `<path>` 우선, 주석 처리에 의한 제외의 근거.

SAP Help — Configuration Options in localextensions.xml (2011, 지원 종료판)
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html?locale=en-US&version=2011
2205판과 같은 설명. 버전이 달라도 규칙이 유지됐다는 보조 근거.

SAP Help — Extension Dependencies (SAP Commerce Cloud, 2211)
https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/20125f0eca6340dba918bda360e3cdfa/8bbf3a9d86691014aa4189bf3ac0eb88.html?locale=en-US
2.1절 `requires-extension` 배경지식(의존성 정의, 빌드 순서 보장, 플랫폼 extension 예외, `load-prior-to`, 같은 alias의 덮어쓰기)의 근거.

SAP Help — Extension Configurations
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8bbf6b5e86691014ad49fd1bc0172e5c.html

SAP Help — Building SAP Commerce
https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/8b6ded0d86691014a6fab18e171c1f91.html

## 2.5 프로그램에서의 처리 (extension-analyzer)

2장의 조사는 [extension-analyzer](../javaTool/extension-analyzer/README.md)가 처리한다.
[spring-bean-analyzer-from-xml](../javaTool/spring-bean-analyzer-from-xml/README.md)은 2장을 다시 판정하지 않고 extension-analyzer의 최신 결과를 읽어서 쓴다.

|2장의 절|extension-analyzer의 처리|결과 파일|
|---|---|---|
|2.1 명시 선언과 검색 경로|`HYBRIS_HOME` 아래의 모든 `extensioninfo.xml`을 찾은 뒤 `localextensions.xml`의 `<extension name/dir>`, `<path dir autoload depth>`를 적용한다. 주석은 XML 파서가 제외한다. 지원하지 않는 `${...}`, 상대 경로, 설치 루트 밖 경로는 추측하지 않고 문제로 남긴다.|`extension-activation.csv`, `lookup-paths.csv`, `activation-issues.csv`|
|2.2 플랫폼 자동 로드와 의존성|`<extensions autoload>`가 켜져 있으면 `bin/platform/ext`를 포함하고, `requires-extension`을 새 이름이 없을 때까지 따라간다.|`extensions.csv`(선택된 것만), `dependencies.csv`, `reverse-dependencies.csv`|
|2.3 중복 이름과 누락|같은 이름은 먼저 적힌 `<path>` 우선, `<extension dir>` 지정 우선, 검색 경로 밖 복사본은 무시한다. 같은 `<path>` 안의 중복과 찾지 못한 의존 대상, 순환을 기록한다.|`extension-duplicates.csv`, `missing-dependencies.csv`, `cycles.csv`, `activation-issues.csv`|
|2.4 빌드 완성 목록과 대조|**하지 않는다.** 빌드 결과나 패키지 인벤토리와의 차이는 사람이 확인한다.|—|

- 판정은 설정 파일만 읽은 정적 추정이다. 실행 중인 서버나 빌드 결과는 확인하지 않는다.
- 스캔이나 판정에 문제가 있으면 `summary.csv`의 `scan_complete`/`selection_complete`가 `false`가 된다. 이때 spring-bean-analyzer-from-xml은 결과를 만들되 종료 코드 1로 끝난다.
- extension-analyzer는 7장 일부(의존성으로 보장되는 선후관계)도 함께 계산해 `extension-load-order.csv`로 낸다.
