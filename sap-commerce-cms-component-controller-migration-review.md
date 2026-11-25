# Accelerator CMS 컴포넌트 컨트롤러의 Composable Storefront 이행 방법 검토

## 1. 질문에 대한 답변

**현행 컴포넌트 컨트롤러의 호출 관계와 Model 데이터를 조사하고, 표준 OCC API와 비교하는 방향은 적절하다. 다만 제시한 세 가지 판단 기준을 그대로 적용하면 불필요한 신규 API를 만들거나, 필요한 데이터·업무 처리를 누락할 수 있다.**

보완해야 할 핵심은 다음 세 가지다.

1. 컴포넌트 컨트롤러 하나를 OCC API 하나로 대체하는 것을 기본 단위로 삼지 않는다. 컴포넌트에 필요한 **CMS 구성 데이터와 업무 데이터**를 분리하여 각각의 제공 경로를 찾는다.
2. Facade·Service 호출의 일치보다 **필요한 데이터와 업무 동작의 동등성**을 최종 기준으로 삼는다.
3. 비슷한 API가 있다는 이유로 신규 API가 필요해지는 것은 아니다. 기존 API의 조합·설정·응답 데이터 확장·업무 계층 확장으로 해결되는지 확인한 뒤 신규 엔드포인트 여부를 결정한다.

이 문서에서 ‘신규 API 추가’는 **새로운 엔드포인트를 공개하는 것**을 의미한다. 기존 URL을 유지한 채 데이터나 업무 처리를 보완하는 것과 구분한다.

| 사용자가 제안한 판단 | 검토 결과 |
|---|---|
| 1. 완전히 대체 가능한 표준 OCC API가 있으면 사용 | 타당하다. 다만 동일한 호출 관계가 필수는 아니며, 하나 또는 여러 표준 API로 충족될 수 있다. |
| 2. 비슷한 표준 API가 있으면 확장하여 새로운 API 작성 | 수정이 필요하다. 기존 엔드포인트를 유지한 확장이나 표준 API 조합으로 충분할 수 있다. |
| 3. 비슷한 표준 API가 없으면 신규 API 추가 | 조건을 보완해야 한다. CMS 데이터나 기존 데이터의 화면 조합으로 해결되지 않고, 서버가 제공해야 할 업무가 남는 경우에 신규 API를 검토한다. |

## 2. 이 문서가 따르는 논의 범위

- 페이지 컨트롤러는 화면 진입점이고, 컴포넌트 컨트롤러는 CMS 컴포넌트 표시를 위한 데이터를 준비하는 컨트롤러라는 용어를 사용한다.
- 각 화면에서 사용하는 페이지 컨트롤러와 컴포넌트 컨트롤러는 이미 찾아 놓았다고 전제한다. 탐색 절차를 다시 논의하지 않는다.
- 현행의 JSP·태그·Renderer를 통한 출력 방식은 배경으로 받아들인다. Renderer의 프런트엔드 이행 구현은 다루지 않는다.
- 페이지 컨트롤러 자체의 이행 설계도 대상에서 제외한다. 단, 컴포넌트가 의존하는 페이지·상품 등의 입력 문맥은 조사 대상에 포함한다.
- 메모로 남긴 ‘새로운 컨트롤러 클래스가 필요한 경우’는 논의에서 제외한다.
- 실제 프로젝트와 대상 버전이 제시되지 않았으므로 아래 내용은 조사 방법론에 대한 검토다. 특정 컴포넌트의 표준 대체 가능성을 확정하는 결과는 아니다.

## 3. 컴포넌트 컨트롤러 이행에서 먼저 구분할 것

### 3.1 CMS 구성 데이터와 업무 데이터는 제공 경로가 다를 수 있다

현행 컴포넌트 컨트롤러의 Model에는 성격이 다른 데이터가 함께 들어갈 수 있다.

| 데이터 성격 | 예시 | 우선 확인할 제공 경로 |
|---|---|---|
| CMS 콘텐츠·설정 | 제목, 본문, 이미지, 링크, 표시 개수, 참조 타입 | 표준 CMS 페이지·컴포넌트 응답 |
| 업무 조회 결과 | 상품 정보, 참조 상품 목록, 가격, 재고 | 해당 업무의 표준 OCC API |
| 사용자·상황별 계산 결과 | 고객별 구매 가능 여부, 계약에 따른 표시 정보 | 문맥과 권한을 처리할 수 있는 업무 API 또는 적합한 서버 확장 |
| 화면 구성 전용 값 | JSP 경로, CSS 클래스 선택, 표시용 형식 | 프런트엔드 담당자와 이행 책임 확인 |

위 표는 우선 검토 순서이며, 모든 값을 반드시 별도 API로 분리해야 한다는 규칙은 아니다.

SAP의 `cmsocc`는 CMS 페이지와 컴포넌트 데이터를 조회하는 OCC API를 제공한다. 따라서 ‘표준 OCC 조사’에는 상품·장바구니 등의 업무 API뿐 아니라 **스토어프런트에서 소비하는 CMS 데이터 API**도 포함해야 한다. CMS 관리용 API와는 구분한다. [SAP: WCMS Module Architecture][sap-cms-architecture]

이에 따라 다음과 같은 구성이 가능하다.

```text
현행 컴포넌트 컨트롤러가 준비하던 데이터
  ├─ CMS 콘텐츠·설정 ──────→ 표준 CMS 페이지·컴포넌트 응답
  ├─ 상품·가격 등의 업무 데이터 → 기존 업무 OCC API 하나 또는 여러 개
  └─ 아직 충족하지 못한 업무 ──→ 필요한 부분만 서버 확장 또는 신규 API 검토
```

이 구성의 성립 여부와 호출 시점은 프런트엔드와 합의해야 하지만, 백엔드에서는 먼저 **어떤 데이터가 어떤 API에서 어떤 조건으로 제공되는지**를 확인할 수 있다.

### 3.2 CMS 응답을 제공한다고 현행 `fillModel` 로직이 자동 이행되지는 않는다

현행 컨트롤러의 `fillModel` 등에서 계산한 값과 CMS 모델에 저장된 속성은 구분해야 한다. CMS 조회 API를 사용한다는 이유만으로 기존 컨트롤러의 계산 로직까지 자동 실행되거나 옮겨졌다고 판단해서는 안 된다.

SAP의 CMS 데이터 제공 계층에는 컴포넌트 조회, 제한 조건 평가, Model→Data 변환 경로가 있다. 현행 MVC 컨트롤러 경로와 실제 CMS API의 데이터 생성 경로를 각각 확인해야 한다. [SAP: Component API][sap-component-api]

참고 문서에 등장하는 `ComponentRenderingService`나 rendering Populator는 CMS 데이터를 제공하는 서버 계층의 명칭이다. 이 문서에서 이를 검토하는 것은 현행 JSP Renderer를 재사용하거나 그 프런트엔드 이행을 조사한다는 의미가 아니다.

## 4. 조사 (1): 현행 컴포넌트 컨트롤러 분석의 보완

### 4.1 Controller→Facade→Service 분석은 유지하되 실제 실행 경로를 따라간다

모든 대상 컴포넌트 컨트롤러의 메서드를 목록화하는 것은 유효하다. 다만 단순한 3단계 호출만 조사하면 다음을 빠뜨릴 수 있다.

- 상속된 요청 처리 메서드와 `fillModel` 구현
- 호출하는 helper, Strategy, Converter, Populator
- 인터페이스에 실제로 주입되는 Spring Bean 구현체
- 호출 인자, 옵션, 분기 조건과 호출 순서
- Controller에서 직접 수행하는 계산·필터링·정렬

SAP Javadoc에서도 `DefaultCMSComponentController`는 공통 상위 컨트롤러를 상속하며 `fillModel`을 구현하는 구조를 확인할 수 있다. 따라서 분석 범위는 해당 클래스에 직접 선언된 메서드만으로 한정하지 않는다. [SAP: DefaultCMSComponentController][sap-default-controller]

예를 들어 데이터 변환 중에 다른 Service가 호출될 수 있으므로 실제 경로는 다음과 같을 수 있다.

```text
Controller → Facade → Service
                  └→ Converter → Populator → 추가 Service
```

SAP는 Facade의 Converter·Populator가 추가 업무 로직을 호출할 수 있음을 설명한다. 데이터 산출 근거를 조사할 때는 이 경로도 포함한다. [SAP: commercefacades Extension][sap-facades]

모든 메서드를 목록화하는 것과 모든 라이브러리 내부를 같은 깊이로 분석하는 것은 구분한다. 공통 호출 경로는 한 번 분석한 결과를 참조하고, 데이터·업무 동작의 차이를 설명할 수 있는 깊이까지 추적한다.

### 4.2 `model.addAttribute` 목록만으로 컴포넌트의 데이터 요구를 확정하지 않는다

Model에 직접 추가하는 속성을 모두 조사하는 것은 필요하다. 그러나 다음 데이터도 확인해야 한다.

- Model에 넣은 `component` 객체의 내부 속성 중 실제로 사용하는 값
- Model에 넣은 Data 객체나 컬렉션의 중첩 속성
- 해당 속성의 getter나 변환 과정에서 추가로 계산되는 값
- 컴포넌트 처리가 읽는 request·session·페이지 문맥
- 상위 처리에서 제공되어 컴포넌트가 실제 사용하는 데이터

예를 들어 `model.addAttribute("component", component)` 한 줄은 프런트엔드가 필요로 하는 속성이 하나라는 뜻이 아니다. 제목·미디어·링크 등 실제 소비 속성을 펼쳐서 비교해야 한다.

백엔드의 데이터 공급 목록과 프런트엔드 담당자의 기존 JSP 데이터 소비 목록을 대조하는 방식이 적절하다. 이는 Renderer 구현을 백엔드 조사 범위에 포함시키는 것이 아니라, 필요한 데이터 계약의 누락을 방지하기 위한 확인이다.

### 4.3 property·value에 의미·생성 조건·문맥을 추가한다

| 조사 항목 | 기록할 내용 |
|---|---|
| 데이터 식별 | 속성명, 중첩 경로, 타입, 리스트·객체 구조 |
| 생성 근거 | CMS 속성, 호출 메서드, 계산식, 상수 |
| 조건 | null·빈 목록·오류 발생 조건, 기본값 |
| 업무 의미 | 무엇을 나타내며 어떤 동작에 영향을 주는가 |
| 문맥 | 상품·카테고리·사이트·언어·통화·사용자·카탈로그 버전 |
| 소비 여부 | 어느 컴포넌트 표시 또는 처리에 필요한가 |

실제 value는 예시로 기록하되 가격·재고 같은 동적 값의 단순 일치를 대체 가능성의 기준으로 삼지 않는다. 같은 조건에서 같은 의미와 산출 규칙을 충족하는지를 비교한다.

### 4.4 컨트롤러 클래스뿐 아니라 컴포넌트 타입·설정·사용 문맥도 구분한다

같은 공통 컨트롤러가 서로 다른 컴포넌트를 처리할 수 있다. 동일한 컴포넌트 타입도 인스턴스별 설정이나 표시되는 페이지에 따라 요구 데이터가 달라질 수 있다.

따라서 공통 코드 분석은 공유하되, 최소한 다음 관계를 남기는 것이 좋다.

```text
컴포넌트 타입 / 인스턴스 또는 설정 그룹
  → 사용하는 컨트롤러와 실제 실행 경로
  → 입력 문맥
  → 필요한 데이터와 업무 규칙
```

설정과 동작이 동등한 인스턴스는 묶어서 관리할 수 있다. 모든 인스턴스의 같은 호출 그래프를 반복 작성할 필요는 없다.

## 5. 조사 (2): 표준 OCC API 분석의 보완

### 5.1 CMS 데이터 API와 업무 API를 함께 조사한다

비교 대상은 다음 두 갈래다.

1. **CMS 데이터 제공**: 페이지·슬롯·컴포넌트 설정과 속성을 제공하는 API
2. **업무 데이터 제공**: 상품·상품 참조·가격·재고·장바구니 등을 제공하는 API

CMS API에서는 Controller→Facade→Service 외에 CMS 모델의 속성 변환 경로도 확인한다. 컴포넌트 속성은 일반적인 Data→WsDTO 매핑과 다른 변환 경로로 제공될 수 있다.

예를 들어 SAP의 `CMSComponentModelToDataRenderingPopulator`는 컴포넌트 모델을 데이터로 변환하는 역할을 가진다. 따라서 유사한 Facade 메서드가 보이지 않는다는 이유만으로 필요한 데이터가 없다고 판단해서는 안 된다. [SAP: CMSComponentModelToDataRenderingPopulator][sap-cms-populator]

### 5.2 JSON의 key·value 외에 요청 조건과 실제 생성 경로를 조사한다

각 후보 API에 대해 다음을 함께 기록한다.

- 요청 URL·HTTP 메서드·파라미터·기본값
- 호출 시 필요한 사이트·상품·사용자 등의 문맥
- 반환 타입과 실제 JSON의 중첩 경로·타입
- 데이터가 생성되는 Facade·Service·변환·계산 경로
- 응답 필드 선택과 조회 옵션
- 접근 권한, 제한 조건, 오류·빈 결과의 의미
- 해당 API의 캐시와 데이터 갱신 조건

응답의 key 이름이 다르더라도 의미가 같고 소비 측에서 변환할 수 있으면 대체 후보가 된다. 반대로 key 이름이 같더라도 단위·산출 조건·권한 범위가 다르면 동등하지 않을 수 있다.

일반 OCC DTO 응답에서는 `fields`와 `BASIC`·`DEFAULT`·`FULL` 등의 설정에 따라 노출 필드가 달라질 수 있다. 이를 사용하는 API는 ‘데이터가 없음’과 ‘현재 선택한 필드 집합에서 빠짐’을 구분한다. 이 설명을 모든 CMS Map 응답에 그대로 적용하지는 않는다. [SAP: Fields Configuration Format][sap-fields]

### 5.3 전수 목록화와 상세 호출 분석은 분리할 수 있다

표준 API를 전수 목록화하는 것은 가능하다. 다만 모든 API의 하위 호출을 같은 깊이로 분석하는 것을 이행 판단의 선행 조건으로 삼을 필요는 없다.

먼저 대상 버전·사용 모듈에서 이용 가능한 API 목록을 만들고, 현행 컴포넌트의 데이터 요구와 연결한 뒤 후보 API를 상세 분석하면 조사량을 줄일 수 있다. 문서에 존재하지만 목표 환경에서 사용할 수 없는 API는 바로 대체 후보로 확정하지 않는다.

## 6. 제시한 세 가지 판단 기준은 어떻게 수정하면 되는가?

### 6.1 판단 1: 완전히 대체 가능한 표준 API가 있다

**표준 API를 사용하는 방향이 맞다.** 다만 ‘완전히 대체 가능’의 정의를 다음과 같이 바꾼다.

> 필요한 CMS 구성 데이터, 업무 데이터, 서버의 업무 규칙을 대상 문맥에서 충족하며, 권한·제한 조건·오류 동작에도 요구사항상 문제가 없다.

현행과 같은 Facade·Service 호출 관계는 유용한 근거지만 필수 조건은 아니다. CMS 응답과 업무 API를 조합하여 충족되는 경우도 여기에 포함할 수 있다.

여기서 권한과 업무 검증은 화면에서 숨기는 것으로 대체하지 않는다. 데이터 접근과 업무 실행을 서버에서도 적절히 제한해야 한다.

### 6.2 판단 2: 완전히 같지는 않지만 비슷한 표준 API가 있다

**‘그러므로 새로운 API가 필요하다’는 결론은 성립하지 않는다.** 먼저 부족한 부분을 분류한다.

| 부족한 부분 | 우선 검토할 방법 | 새 엔드포인트 |
|---|---|---|
| CMS 설정값은 CMS 응답에 있고, 업무 데이터도 표준 API에 있음 | 두 제공 경로의 조합 | 불필요할 수 있음 |
| 필요한 필드가 이미 있으나 현재 응답에서 제외됨 | 해당 API의 필드 선택·조회 옵션 조정 | 대체로 불필요 |
| 기존 리소스에 업무 속성만 부족함 | 필요한 Data·Populator·WsDTO·매핑 확장 | 불필요할 수 있음 |
| CMS 콘텐츠·구성 속성의 제공이 부족함 | 해당 CMS 변환·속성 제공 경로 확장 | 불필요할 수 있음 |
| 기존 업무에 서버 검증·계산·필터링이 부족함 | 적합한 Facade·Service·Strategy·Hook 확장 | 불필요할 수 있음 |
| 기존 요청·응답 계약을 유지하기 어려운 별도 업무가 필요함 | 별도 API 계약 설계 | 추가 검토 |

위 행들은 함께 적용될 수 있다. 예를 들어 응답 속성을 추가하면서 그 값을 계산할 Service도 확장할 수 있다.

SAP는 Facade의 빈 교체·상속·조합 및 Converter·Populator 변경을 확장 방법으로 설명한다. 또한 CMS 속성 제공을 위한 `CMSItemPropertiesSupplier` 기반 확장도 문서화하고 있다. 실제 적용할 수 있는 경로는 해당 버전과 응답 계약에 맞춰 선택한다. 모든 값을 공통 `properties`에 넣으라는 의미는 아니다. [SAP: commercefacades Extension][sap-facades], [SAP: Custom Properties for Rendering][sap-custom-properties]

확장할 때는 다음 두 가지를 별도로 검토한다.

- **적용 범위**: 공통 Service·Populator의 변경이 다른 API·사이트·컴포넌트에도 적용되는가?
- **데이터 문맥과 캐시**: 사용자별 결과를 CMS 공통 응답에 넣을 때 인증·캐시 구분·조회 비용이 적절한가?

특정 컴포넌트의 요구만을 위해 공통 API의 의미를 바꾸기보다, 공통 계약을 유지할 수 있는지 먼저 확인한다. 유지하기 어렵다면 별도의 계약을 가진 API를 검토한다.

### 6.3 판단 3: 비슷한 표준 API가 없다

**서버가 제공해야 할 미충족 업무가 남아 있는 경우에는 신규 API를 추가하는 방향이 타당하다.** 다만 그 전에 다음을 확인한다.

1. 이미 CMS 속성으로 제공되는 콘텐츠·설정은 아닌가?
2. 표준 API 여러 개를 조합하면 충족되지 않는가?
3. 단순 표시 형식이나 기존 데이터의 화면 조합인가?
4. 서버 권한·업무 규칙을 적용하여 새로 조회·계산해야 할 데이터인가?

1~3으로 충족되지 않고 4에 해당하면 신규 API의 필요성이 분명해진다. 새 API에서도 기존 커스텀 Facade·Service를 재사용할 수 있으므로, API가 없다는 사실과 업무 구현이 없다는 사실은 구분한다.

화면에서 조합 가능한 것과 서버에서 강제해야 할 업무 규칙은 구분해야 한다. 프런트엔드가 계산할 수 있다는 이유만으로 권한·구매 자격 등의 판단을 전부 넘기지는 않는다.

## 7. 컴포넌트에 적용한 예시

### 7.1 배너 컴포넌트

현행에서 제목·이미지·링크를 Model로 전달한다고 가정한다.

표준 CMS 응답에 필요한 콘텐츠와 미디어 정보가 있고 목표 프런트엔드의 데이터 요구를 충족한다면, 컴포넌트 전용 업무 API를 새로 만들 이유가 없다. 현행과 동일한 Facade·Service 호출이 발견되지 않아도 마찬가지다.

SAP의 배너 문서는 `headline`, `content`, `urlLink`, `media` 등의 CMS 컴포넌트 데이터를 표시하는 구조를 설명한다. 다만 컴포넌트 타입에 따라 제공 속성이 다를 수 있으므로 실제 응답을 확인한다. [SAP: Banner Component][sap-banner]

### 7.2 상품 참조 컴포넌트

현행 동작이 다음과 같다고 가정한다. 아래는 분석 예시이며, 특정 버전의 `ProductReferencesComponentController` 구현을 그대로 인용한 것은 아니다.

```text
CMS 설정에서 참조 타입·표시 개수 확인
  → 현재 상품 코드 확인
  → 참조 상품 조회
  → 결과를 Model에 설정
```

우선 다음 대응을 검토한다.

| 현행 요구 | 이행 후보 |
|---|---|
| 제목·참조 타입·표시 개수 | 표준 CMS 응답에서 제공되는지 확인 |
| 현재 상품 코드 | 현재 페이지의 상품 문맥을 API 입력으로 전달 |
| 참조 상품 목록 | 표준 상품 참조 OCC API |
| 추가 가격·재고 등 | 참조 응답의 필드 또는 별도 업무 API로 충족되는지 확인 |
| 현행의 커스텀 필터링·정렬 | 표준 기능과 차이를 확인한 뒤 업무 계층 확장 여부 결정 |

SAP Commerce 2211.4의 `ProductsController`에는 상품 코드에 대한 참조 조회 메서드가 있으며 `referenceType`, `pageSize`, `fields` 입력을 확인할 수 있다. 이것은 후보 API가 있다는 근거이지, 현행 컴포넌트와 모든 의미가 같다는 증명은 아니다. [SAP: ProductsController][sap-products-controller]

필터링 기준·정렬·최대 개수 적용 순서·품절 상품 처리 등 현행의 실제 규칙을 비교해야 한다. 예를 들어 ‘N개 조회 후 필터링’과 ‘필터링 후 N개 조회’는 최종 표시 결과가 다를 수 있다.

이 예시는 컴포넌트 하나의 요구를 **CMS 데이터 + 상품 문맥 + 업무 API**로 충족할 수 있음을 보여준다.

## 8. 보완한 조사·판단 절차와 산출물

1. **분석 기준을 고정한다.**
   - 현행과 목표 버전, 이용할 OCC 모듈, 컴포넌트별 요구 기능을 정한다.
2. **이미 식별한 현행 컴포넌트의 실행 경로와 데이터 의존성을 분석한다.**
   - 상속·실제 Bean·변환 경로와 입력 문맥을 포함한다.
3. **필요한 데이터를 CMS 구성·업무 데이터·화면 전용 값으로 분류한다.**
   - 프런트엔드의 소비 데이터 목록과 대조한다.
4. **표준 CMS 데이터 API와 업무 API를 연결한다.**
   - 하나의 컴포넌트에 여러 제공 경로를 연결할 수 있다.
5. **미충족 부분만 분류하여 구현 방식을 결정한다.**
   - 설정·조합 → 기존 데이터·업무 확장 → 별도 계약이 필요한 신규 API 순으로 검토한다.
6. **대표 문맥에서 동등성을 확인한다.**
   - 정상 결과, 빈 결과, 권한, 제한 조건, 언어·통화, 필요한 경우 미리보기·카탈로그 버전을 확인한다.
   - 현행의 동일 페이지 요청 안에 있던 정보가 별도 API 호출에도 제공되는지 확인한다. 이전 요청의 MVC Model이 자동 전달된다고 가정하지 않는다.

구현 결정의 근거를 남길 조사표는 다음 형태가 적절하다.

| 항목 | 기록 내용 |
|---|---|
| 대상 | 화면, 컴포넌트 타입·인스턴스 또는 설정 그룹, 컨트롤러 |
| 현행 경로 | 메서드, 실제 Bean, Facade·Service·변환 경로 |
| 현행 데이터 | Model 및 중첩 속성, 타입, 산출 규칙, 실제 사용 여부 |
| 입력 문맥 | 상품·카테고리·사용자·사이트 등 필요한 조건 |
| 대체 후보 | CMS API·업무 API, 파라미터, JSON 경로 |
| 차이 | 데이터·업무·권한·제한 조건·오류·캐시상의 미충족 부분 |
| 결정 | 표준 사용·설정·조합·기존 확장·신규 API·프런트엔드 인계 |
| 검증 근거 | 비교한 시나리오, 소스·설정 위치, 실제 응답, 남은 확인 사항 |

이 표의 판단 단위는 ‘같은 컨트롤러가 존재하는가’가 아니라 **컴포넌트에 필요한 데이터와 서버 동작이 어떤 경로로 충족되는가**다.

## 9. 참고한 SAP 공식 문서

이 문서의 조사·판단 절차는 공식 문서의 구조를 바탕으로 제안한 방법론이다. SAP가 이 문서와 동일한 절차를 공식 이행 절차로 규정한 것은 아니다. 각 링크의 버전과 실제 대상 환경의 차이는 적용 시 확인해야 한다.

- [WCMS Module Architecture][sap-cms-architecture]: CMS 관련 확장과 `cmsocc`의 역할
- [Component API][sap-component-api]: CMS 속성 데이터 제공, 제한 조건, 문맥과 변환 경로
- [DefaultCMSComponentController, 2211 Javadoc][sap-default-controller]: 공통 컴포넌트 컨트롤러와 상속 구조
- [commercefacades Extension][sap-facades]: Facade의 역할, Converter·Populator와 확장 방식
- [CMSComponentModelToDataRenderingPopulator, 2211.4 Javadoc][sap-cms-populator]: 컴포넌트 Model→Data 변환
- [Fields Configuration Format][sap-fields]: OCC 데이터 매핑의 응답 필드 선택
- [Custom Properties for Rendering][sap-custom-properties]: CMS 속성 제공 확장 구조
- [Banner Component][sap-banner]: CMS 배너 데이터의 소비 방식
- [ProductsController, 2211.4 Javadoc][sap-products-controller]: 표준 상품 참조 조회의 입력과 응답 타입

[sap-cms-architecture]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/e1391e5265574bfbb56ca4c0573ba1dc/4cf11877c72143419a948f06943c62d3.html
[sap-component-api]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/e1391e5265574bfbb56ca4c0573ba1dc/4436bc02c61d4a958fd816619594343d.html
[sap-default-controller]: https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2211/en-US/de/hybris/platform/yacceleratorstorefront/controllers/cms/DefaultCMSComponentController.html
[sap-facades]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/e1391e5265574bfbb56ca4c0573ba1dc/8b832e6286691014a050d0863227d73b.html
[sap-cms-populator]: https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-US/de/hybris/platform/cmsfacades/rendering/populators/CMSComponentModelToDataRenderingPopulator.html
[sap-fields]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/e1391e5265574bfbb56ca4c0573ba1dc/730208af21004b9c92fe493121daac23.html
[sap-custom-properties]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/e1391e5265574bfbb56ca4c0573ba1dc/6154a3185bb84444b96b9079b102e357.html
[sap-banner]: https://help.sap.com/docs/SAP_COMMERCE_COMPOSABLE_STOREFRONT/eaef8c61b6d9477daf75bff9ac1b7eb4/19e41fc48611408eaf726d64fff6e976.html
[sap-products-controller]: https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-US/de/hybris/platform/commercewebservices/core/v2/controller/ProductsController.html
