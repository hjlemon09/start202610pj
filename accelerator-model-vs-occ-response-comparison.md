# Accelerator Model과 OCC Response를 비교해 대체 후보 OCC API를 고르는 방법 검토

## 질문

현재 계획은 다음과 같다.

- accelerator 컨트롤러가 `model`에 어떤 property와 value를 설정하는지 알아낸다.
- OCC API 컨트롤러가 response 객체에 무엇을 설정하는지 알아낸다.
- 둘을 비교해 accelerator 컨트롤러마다 대체 후보가 될 OCC API를 고른다.

staticAnalyzer가 쓰는 두 가지 방법을 섞어서 이 작업을 할 수 있는지 검토한다.

- 인자 식의 모양으로 타입을 추정하는 방법
- `.class` 파일에서 타입을 읽는 방법

더 나은 방법이 있으면 함께 제시한다.

관련 문서: [javaTool/staticAnalyzer/overload-resolution.md](javaTool/staticAnalyzer/overload-resolution.md)

---

## 1. 결론

**후보를 고르는 데까지는 가능하다. 다만 두 방법만으로는 부족하고, 비교 단위와 정보원을 넓혀야 한다.**

1. 두 방법으로 확실히 알 수 있는 것은 **"어떤 key에 어떤 타입의 객체가, 어떤 facade·service 호출로 만들어져 들어가는가"**까지다.
   - 예: `productReferences` ← `List<ProductReferenceData>` ← `ProductFacade.getProductReferences(...)`
   - 이것만으로도 후보 OCC API를 좁히는 1차 선별은 충분히 할 수 있다.
2. 그 객체 안에 **실제로 어떤 property가 채워지는지**는 Java 코드의 타입만으로 정해지지 않는다. 다음이 함께 결정한다.
   - 인자의 **값**: 예를 들어 `ProductOption` 목록
   - Spring XML의 populator 구성
   - OCC 쪽의 `fields` 레벨 매핑과 Orika 매핑
   - JSP가 실제로 사용하는 속성

   그래서 타입 추정에 **값 추적, XML 해석, JSP 해석**을 더해야 한다.
3. 더 나은 방법으로 **정적 분석으로 후보를 고르고, 실행 결과로 검증하는 2단계 방식**을 권한다.
   - 정적 분석은 누락 없이 넓게 고르는 데 강하다.
   - "같은 데이터가 정말 나오는가"는 실제로 실행해서 덤프한 결과를 비교하는 편이 훨씬 싸고 정확하다.

---

## 2. 비교 단위는 "property-value"가 아니라 "Data 객체와 그 속성 경로"여야 한다

accelerator와 OCC의 데이터 흐름을 나란히 놓으면 다음과 같다.

```text
[accelerator]
Model ← facade.getXxx(args) → XxxData ──(JSP의 EL이 필요한 속성만 꺼내 씀)──▶ HTML

[OCC]
response ← dataMapper.map(XxxData, XxxWsDTO.class, fields) ← facade.getXxx(args) → XxxData
```

두 쪽 모두 **중간에 같은 `XxxData`(beans.xml에서 생성된 DTO)를 거친다.** 따라서 비교는 다음 3층으로 나누면 깔끔하다.

| 층 | 비교 내용 | 알아내는 방법 |
|---|---|---|
| A. 생성 경로 | 같은 facade·service 메서드를, 같은 의미의 인자로 부르는가 | 현재 call tree와 오버로드 판정 (이미 있음) |
| B. 객체 타입 | model value의 타입과 `map()`에 들어가는 source 타입이 같은 Data인가 | 타입 추정 + 보완 (3장) |
| C. 속성 경로 | JSP가 쓰는 속성 ⊆ OCC 응답(지정 fields 레벨)에 나오는 속성인가 | 값 추적 + XML·JSP 해석, 또는 실행 덤프 (3·4장) |

A와 B가 맞으면 **후보**, C까지 맞으면 **대체 가능**으로 판정한다.

"model에 설정하는 property-value"를 그대로 비교하기는 어렵다. model의 value는 대개 `ProductData` 같은 **객체 통째**이고, 그 객체의 어느 속성이 필요한지는 컨트롤러가 아니라 JSP가 정하기 때문이다.

---

## 3. 정적 분석만으로 갈 경우 추가해야 할 것

현재 staticAnalyzer는 호출 트리를 만든다. model이나 response에 무엇을 넣는지는 아직 추출하지 않는다(`addAttribute` 처리 없음). 정적 분석으로 진행하려면 아래가 필요하다.

### 3.1 accelerator 쪽: model 설정 지점 추출

- 다음 호출을 찾아 **key**와 **value 식**을 뽑는다.
  - `model.addAttribute("key", expr)`
  - `model.addAttribute(expr)`: key가 타입 이름에서 자동으로 정해진다.
  - `ModelAndView.addObject`, `request.setAttribute`
  - `@ModelAttribute` 메서드
- key가 `WebConstants.XXX` 같은 상수인 경우가 많다. 상수 값을 읽으려면 다음이 필요하다.
  - 소스가 있으면 필드 초기값을 읽는다.
  - 소스가 없으면 `.class`의 `ConstantValue` 속성을 읽는다. 지금의 class 파서는 이 속성을 건너뛰므로 추가해야 한다.
- **`DefaultCMSComponentController`는 컴포넌트 자신의 속성을 동적으로 model에 넣는다.** 컴포넌트의 편집 가능 속성을 돌면서 `model.addAttribute(property, value)`를 하는 구조라서, key가 코드에 적혀 있지 않다.
  - 이 부분은 컴포넌트 타입 정의(`*-items.xml`) 쪽에서 속성 목록을 가져와야 한다.
  - Composable Storefront에서는 이 데이터가 개별 OCC API가 아니라 **CMS 페이지·컴포넌트 API**(`/cms/pages`, `/cms/components`)로 내려오므로 비교 대상도 그쪽이다. 정확한 적용 범위는 대상 버전 소스로 확인이 필요하다.

### 3.2 타입 추정 보완

지금의 `TRef`는 **erasure(단순명)만** 들고 있다. 이 용도에는 다음이 필요하다.

- **제네릭 인자 유지**: `List<ProductReferenceData>`에서 `List`만 남으면 비교할 수 없다.
  - 소스는 AST에 제네릭 정보가 있으니 보존하면 된다.
  - `.class`는 `Signature` 속성을 파싱해야 한다. 지금은 `T`인지 여부만 본다.
- **`dataMapper.map(x, XxxWsDTO.class, fields)`의 반환 타입**: 선언상 반환 타입이 `T`라서 지금은 "모름"이 된다. 두 번째 인자의 class literal로 반환 타입을 정하는 **전용 규칙**이 필요하다.
- **`Converter<S, T>.convert()`**: 주입된 필드 선언의 제네릭 인자에서 반환 타입을 정한다.

### 3.3 값 추적 (타입이 아니라 값이 결과를 바꾸는 곳)

- 대표적인 예가 `ProductFacade.getProductForCodeAndOptions(code, options)`다.
  - 어떤 populator가 돌지가 **`ProductOption` 목록의 값**으로 정해진다.
  - 같은 메서드를 불러도 `BASIC`만 주면 가격이 없고, `PRICE`를 주면 있다.
- 따라서 인자에 대해 **상수 전파**가 필요하다. 다음 경우를 다룬다.
  - `Arrays.asList(ProductOption.BASIC, ...)`
  - static 필드에 정의된 목록
  - 지역 변수에 담아 넘기는 경우

  분기마다 값이 달라지면 "여러 값"으로 표시한다.
- OCC 쪽은 options가 고정이거나 `fields`에 따라 달라지는 경우가 있으므로 같은 방법으로 추적한다.

### 3.4 코드 밖의 정보

| 정보 | 위치 | 쓰는 곳 |
|---|---|---|
| Data, WsDTO의 속성 목록 | `*-beans.xml`, 또는 `bootstrap/gensrc` 소스·`.class` getter | 층 C의 속성 집합 |
| option → populator 매핑, populator 목록 | 각 extension의 Spring XML (`*-spring.xml`) | 어떤 Data 속성이 채워지는가 |
| `fields` 레벨(BASIC/DEFAULT/FULL) → WsDTO 속성 | OCC extension의 Spring XML (`fieldSetLevelMapping` 빈) | OCC 응답에 실제로 나오는 속성 |
| Data → WsDTO 이름이 다른 매핑 | Orika `fieldMapper`·커스텀 converter 빈 | 속성 이름 대응표 |
| JSP가 쓰는 속성 | 컴포넌트 JSP의 EL (`${product.price.formattedValue}`) | "필요한 속성" 집합 |

Spring XML은 `modifyPopulatorList`, alias, 빈 오버라이드로 extension마다 덮어쓴다. 정적으로 정확히 해석하려면 [sap-commerce-spring-context-analyzer](sap-commerce-spring-context-analyzer/) 절차서(특히 [7장 extension 사이의 로드 순서](sap-commerce-spring-context-analyzer/07-extension-사이의-로드-순서-확인.md))에서 정리한 수준의 로드 순서 해석이 필요하다. 이 부분이 정적 방식에서 가장 비용이 크다.

### 3.5 정적 방식만으로의 판단

- 층 A·B(후보 선별): **현실적으로 가능하다.** 지금 도구의 연장선에서 3.1·3.2를 추가하면 된다.
- 층 C(속성 단위 확정): **가능하지만 비용이 크고 오차가 남는다.** 3.3·3.4를 모두 구현해야 하고, 런타임 분기, 커스텀 populator 안의 조건문, 빈 오버라이드를 완벽히 재현하기는 어렵다.

---

## 4. 권장안: 정적 분석으로 후보 선별, 실행 덤프로 검증

### 4.1 1단계 — 정적 분석 (층 A·B)

staticAnalyzer를 확장해 컨트롤러 메서드마다 다음 표를 만든다.

```text
[accelerator]
컨트롤러#메서드 | model key | value 타입(제네릭 포함) | 생성 facade 메서드 | 주요 인자 값(options 등)

[OCC]
엔드포인트(HTTP 메서드 + 경로) | 컨트롤러#메서드 | source Data 타입 | 생성 facade 메서드 | 주요 인자 값 | 응답 WsDTO 타입
```

두 표를 **(Data 타입, facade 메서드)를 key로 조인**해 후보 OCC API 목록을 만든다.

- 같은 facade 메서드 + 같은 Data 타입이면 **유력 후보**
- 같은 Data 타입이지만 facade 메서드가 다르면 **약한 후보**
- 어느 쪽도 없으면 **신규 API 검토 대상**

이 단계는 누락 없이 전체를 훑는 것이 목적이므로 정적 분석이 적합하다.

### 4.2 2단계 — 실행 덤프 (층 C)

로컬 개발 환경에서 실제로 실행해 **채워진 속성 경로**를 수집한다.

- **accelerator 쪽**
  - 컴포넌트 컨트롤러도 Spring MVC 컨트롤러이므로, 로컬에서만 켜는 `HandlerInterceptor`의 `postHandle`에서 `ModelAndView`의 model을 덤프할 수 있다.
  - 덤프는 Jackson 등으로 JSON화한 뒤, null이 아닌 속성 경로 목록(`productReferences[].target.price.formattedValue`)으로 평탄화한다.
- **OCC 쪽**
  - 1단계의 후보 API를 같은 데이터(같은 상품·사용자·사이트)로 호출한다.
  - `fields=FULL`과 실제 쓸 레벨로 각각 호출하고 응답 JSON을 같은 방식으로 평탄화한다.
- **비교**
  - 필요한 속성 경로 ⊆ OCC 응답 속성 경로인지 확인한다.
  - Data와 WsDTO의 이름이 다른 속성은 3.4의 Orika 매핑으로 이름을 맞춘 뒤 비교한다.
  - 필요한 속성은 다음 중 하나로 정한다.
    - accelerator model 덤프 전체(보수적)
    - JSP EL에서 추출한 속성만(정확)

실행 방식의 장점은 다음과 같다.

- populator 구성, 빈 오버라이드, options 값, `fields` 매핑을 **해석하지 않아도 결과에 이미 반영되어 있다.**
- 3.3·3.4의 구현 비용 대부분이 사라진다.

단점은 다음과 같다.

- **테스트 데이터가 지나간 분기만 보인다.** 예를 들어 재고 없는 상품, 로그인 사용자, B2B 계정에서만 나오는 속성은 그 조건의 데이터로 따로 실행해야 한다.
- 실행 환경이 필요하다.

정적 분석 결과(인자 값, 분기)를 보고 **어떤 조건의 데이터로 실행해야 하는지** 정하면 이 단점을 보완할 수 있다.

### 4.3 판정 등급

| 등급 | 조건 | 대응 (기존 이행 방침의 분류) |
|---|---|---|
| 대체 가능 | A·B 일치, C에서 필요한 속성 전부 포함 | 해당 OCC API로 대체 |
| 확장 후보 | B 일치, C에서 일부 속성 부족 | 비슷한 표준 API 확장 (WsDTO 속성 추가, fields 레벨 조정 등) |
| 신규 | B 불일치 | 신규 API 추가 |
| CMS API | `DefaultCMSComponentController`가 넣는 컴포넌트 자체 속성 | CMS 페이지·컴포넌트 API로 처리되는지 확인 |

---

## 5. 작업 순서 제안

1. staticAnalyzer에 model 설정 지점 추출(3.1)과 타입 보완(3.2)을 추가하고, 1단계 표를 출력한다.
2. 두 표를 조인해 후보 목록을 만든다. 이 시점에서 사람이 한 번 검토한다.
3. 인자 값 추적(3.3)은 `ProductOption`처럼 결과를 크게 바꾸는 인자에 한정해 먼저 넣는다.
4. 로컬 환경에 덤프용 interceptor를 넣고, 후보 API 호출 스크립트로 층 C를 검증한다.
5. 덤프만으로 판단이 어려운 곳만 Spring XML·Orika 매핑을 정적으로 확인한다.

---

## 6. 주의할 점

- **"같은 facade 메서드 = 같은 데이터"는 아니다.** 인자 값(options), populator 구성, OCC의 `fields` 레벨에 따라 결과가 달라진다. 1단계 결과는 어디까지나 후보다.
- **OCC는 기본 `fields=DEFAULT`이고, 프런트가 요청하는 레벨에 따라 응답이 달라진다.** 비교할 때는 Composable Storefront가 실제로 보낼 `fields` 값을 기준으로 한다.
- **model에 넣는다고 해서 화면에서 쓰는 것은 아니다.** 필요한 속성을 model 전체로 잡으면 "부족"으로 과하게 판정될 수 있다. 가능하면 JSP EL 사용 속성으로 좁힌다. 이 부분은 프런트엔드 담당자의 조사와 맞춰 보는 것이 좋다.
- **실행 덤프는 테스트 데이터에 의존한다.** 조건별 데이터를 준비하지 않으면 "포함됨"으로 잘못 판정될 수 있다. 덤프에 나오지 않은 속성은 "확인되지 않음"으로 기록한다.
