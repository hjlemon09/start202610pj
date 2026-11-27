# Accelerator 엔드포인트를 OCC 엔드포인트로 옮길 때의 이행 패턴 검토

작성일: 2026-11-27 (논의 전제 반영판)

질문 원문은 `Question.txt`에 있다. 질문 번호(0~0-5)에 맞춰 답한다. 버전에 따라 동작이 다를 수 있는 부분은 **[버전 확인]**으로 표시했다. 대상 버전(2211-jdk21)의 실제 소스와 설정으로 확인해야 한다.

---

## 전제와 그 전제가 답에 주는 영향

### 논의의 전제 (요약)

- 현행 Accelerator와 같은 소스·DB로 시작하고, zip에 포함된 OCC extension을 활성화해 OCC 구성(essential/project data, OAuth 클라이언트)을 더한다.
- Accelerator 관련 파일은 삭제하지 않고 충돌할 때만 조정한다.
- 작업의 약 90%는 Controller 계층이다. facade·service·dao·model은 재사용하고, 정말 필요할 때만 추가한다(model은 확실한 근거가 있을 때만).

### 전제에서 바로 나오는 결론 6가지

1. **이행의 중심은 "Accelerator 컨트롤러가 하던 일을 OCC 컨트롤러로 옮기는 일"이다.** 메서드의 처리를 facade 호출(재사용), 컨트롤러 안의 로직(OCC 컨트롤러로 이전, 정말 필요하면 facade로), 화면 처리(프런트엔드·CMS)로 나눈다.
2. **Data 객체는 이미 있다.** Accelerator 화면의 커스텀 값은 대부분 `*Data`와 Populator로 채워져 있다. 그래서 0-2-1은 많은 경우 **WsDTO 필드와 fields 매핑 추가로 끝난다.**
3. **Accelerator 스토어프런트가 같은 서버에 남는다.** facade·service·Populator를 바꾸면 OCC와 Accelerator 화면에 **동시에** 적용된다. OCC 웹 계층(컨트롤러, WsDTO, OCC용 web-spring 빈)만 바꾸면 Accelerator에는 영향이 없다.
4. **재사용할 수 있는 것은 "코어 쪽 빈"뿐이다.** 스토어프런트 웹 애플리케이션 컨텍스트의 빈은 OCC 웹 컨텍스트에서 보이지 않는다(아래 표).
5. **Facade가 세션을 쓰는 것은 대체로 괜찮다.** OCC는 요청마다 사이트·사용자·통화·언어·장바구니(`cartId`)를 세션에 설정하므로 `getSessionCart()` 같은 호출도 동작한다. 다만 **Accelerator 컨트롤러나 필터가 HTTP 세션에 직접 넣던 커스텀 속성**은 OCC에 없다.
6. **DB를 그대로 가져오므로 CMS 데이터도 그대로 있다.** Composable Storefront는 이를 CMS API로 읽으므로, 화면 구성 일부는 새 OCC 엔드포인트 없이 이행할 수 있다(0-5).

### 재사용 가능 여부: 정의 위치로 판정

| 정의 위치 | OCC에서 재사용 | 비고 |
|---|---|---|
| core·facades extension의 `*-spring.xml` (글로벌·코어 컨텍스트) | **가능** | facade, service, dao, converter, populator, strategy |
| `*-beans.xml`의 Data 클래스 | **가능** | WsDTO는 별도로 필요 |
| items.xml의 model | **가능** | 추가하지 않는 전제와 맞다 |
| 스토어프런트 `*-web-spring.xml`, `spring-mvc-config.xml` 등 웹 컨텍스트 | **불가** | 폼 Validator, 컨트롤러 헬퍼, 스토어프런트 전용 strategy, 인터셉터 |
| 스토어프런트 `src`의 클래스 (Form, Validator, util) | **불가**(클래스 경로가 다름) | OCC extension에 다시 만들거나, 공용 extension으로 옮긴다. 옮기는 것은 "충돌 시에만 조정" 원칙과 맞는지 판단이 필요하다 |
| addon의 웹 쪽 빈 (`*addon/resources/.../web/spring`) | **불가** | addon의 코어 쪽 빈은 가능 |

spring-bean-analyzer-from-xml의 결과(빈이 어느 컨텍스트에 등록되는지)로 판정할 수 있다. **[버전 확인]** 커스텀 OCC extension의 웹 컨텍스트가 무엇을 import하는지도 확인한다.

---

## 0. 이행 작업에서 나타나는 패턴의 전체 그림

### Accelerator 엔드포인트의 종류와 이행 단위

| Accelerator 쪽 종류 | OCC 쪽 이행 단위 |
|---|---|
| 페이지 컨트롤러 (JSP 뷰 반환) | **1:N**. CMS 페이지 API + 여러 OCC API + 프런트엔드 처리로 분해 |
| AJAX/JSON 컨트롤러 (`@ResponseBody`) | **1:1에 가깝다**. 대응 OCC API를 찾거나 새로 만든다 |
| 폼 POST + 리다이렉트 | 처리는 OCC의 POST/PUT/PATCH, 화면 이동은 프런트엔드 라우팅 |

### 패턴 목록

엔드포인트를 업무 기능(조회 하나, 갱신 하나) 단위로 쪼갠 뒤, 기능마다 아래 패턴 중 하나 이상을 고른다.

| 패턴 | 요약 | 이 전제에서의 주 작업 | Accelerator 화면 영향 |
|---|---|---|---|
| A. 표준 API로 대체 (0-1) | 표준 OCC + Spartacus 표준 기능 | 없음(필요한 OCC extension 활성화) | 없음 |
| B. 표준 API 조합 (0-5) | 표준 API 여러 개를 프런트엔드에서 조합 | 프런트엔드만 | 없음 |
| C. 응답 데이터 확장 (0-2-1) | 같은 URL의 응답에 필드 추가 | WsDTO + fields 매핑. Data·Populator는 대개 이미 있음 | Populator를 새로 넣을 때만 있음 |
| D. 하위 업무 로직 확장 (0-2-2) | 같은 계약, 처리 규칙만 변경 | Service·Strategy·Hook | **있음**(공유 계층) |
| E. 요청 계약 확장 (0-2-2) | 선택 파라미터, 요청 DTO 필드 추가 | WsDTO, 컨트롤러 재정의 | 없음 |
| F. 같은 URL 재정의 (0-2-2) | `@RequestMappingOverride`로 처리 교체 | 커스텀 OCC 컨트롤러 | 없음 |
| G. 신규 엔드포인트 (0-3) | 새 URL, 새 계약 | 커스텀 OCC 컨트롤러 + 기존 facade 호출 | 없음 |
| H. API가 필요 없음 (0-5) | CMS, 프런트엔드, 설정 | CMS 컴포넌트 매핑 | 없음 |

이 전제에서는 **G가 가장 많이 나올 가능성이 높다.** facade를 재사용하므로, 표준 OCC에 없는 기능은 대부분 "기존 facade를 부르는 새 OCC 컨트롤러"가 된다. C와 F가 그 다음이다. D는 이 전제에서 최소화하는 것이 맞다(Accelerator 화면에도 영향).

---

## 0-1. 기존 OCC API로 완전히 대체할 수 있는 경우

### 대체 가능 판정 조건 (모두 만족)

1. **입력**: Accelerator가 받던 파라미터를 OCC API의 경로·쿼리·본문으로 표현할 수 있다.
2. **처리**: 호출되는 facade/service가 같거나 업무상 같은 결과를 낸다. 부수 효과(이벤트, 메일, 감사 로그)도 포함한다.
   - 이 전제에서는 **같은 facade를 부르는지**를 호출 그래프로 비교하면 판정이 빠르다.
3. **출력**: 화면에 필요한 값이 OCC 응답(fields 지정 포함)에 모두 있다.
4. **조건**: 권한, 로그인 여부, 사이트·통화·언어 컨텍스트가 같은 방식으로 적용된다.

### 주의할 차이

- **컨트롤러 안의 로직**
  - Accelerator 컨트롤러가 facade 호출 전후에 하던 처리를 찾는다(값 보정, 조건 분기, 추가 facade 호출).
  - 이것이 있으면 "같은 facade를 부른다"만으로는 대체가 아니다.
- **세션 의존**: Accelerator 컨트롤러가 HTTP 세션에 넣던 커스텀 값은 OCC에 없다.
- **fields 차이**
  - "값이 없다"고 판정하기 전에 `FULL`이나 필드를 명시 지정해서 다시 확인한다.
  - occ-response-analyzer의 `occ-fieldset-mappings.csv`로 레벨별 필드를 볼 수 있다.
- **OCC extension 미활성**: B2B 등은 별도 OCC extension(예: `b2bocc`)을 켜야 API가 생긴다. 전제대로 zip에 있으므로 활성화 대상인지 확인한다.

### 이행 작업

- 백엔드: 없음, 또는 필요한 OCC extension 활성화.
- 프런트엔드: Spartacus 표준 컴포넌트·서비스를 그대로 쓴다. 필요하면 endpoint 설정의 `fields`만 조정한다.
- Accelerator 컨트롤러는 삭제하지 않고 남긴다(전제). OCC로 옮기지 않을 뿐이다.

---

## 0-2. 기존 OCC API를 일부 수정하는 경우

### 0-2-1. 같은 엔드포인트에 응답 항목을 추가하기

#### 구현 순서 (이 전제에서의 형태)

```text
[대개 이미 있음]
1. *Data 빈의 커스텀 속성       ← Accelerator 화면용으로 이미 정의됨
2. Populator                    ← Accelerator 화면용으로 이미 등록됨

[새로 하는 일]
3. *WsDTO 빈에 같은 이름의 속성 추가 (커스텀 OCC extension의 beans.xml)
     → DataMapper(Orika)가 이름이 같으면 자동 매핑. 이름·타입이 다르면 매핑 설정 필요
     → 커스텀 Data 타입이면 그에 대응하는 WsDTO 타입도 새로 정의
4. FieldSetLevelMapping에 필드 추가 (BASIC / DEFAULT / FULL 중 어디에 넣을지 결정)
5. Spartacus: 모델 타입 확장 + endpoint fields 설정 + 표시 컴포넌트
```

- 1·2가 **OCC가 부르는 Converter에도 등록되어 있는지** 확인한다.
  - Accelerator 컨트롤러는 다른 facade 메서드나 다른 ProductOption으로 같은 Data를 채웠을 수 있다.
  - 예: 상품 상세는 `ProductOption` 목록에 따라 실행되는 Populator가 달라진다. **[버전 확인]**
- 4를 하지 않아도 `fields=...,originCountry`처럼 명시 지정하면 응답에 나온다. 레벨에 넣는 것은 "레벨로만 fields를 지정하는 호출자에게도 기본으로 보일지"를 정하는 일이다.
- 같은 `dtoClass`에 FieldSetLevelMapping 빈을 하나 더 정의해 합치는 방식이 쓰인다. **[버전 확인]** occ-response-analyzer는 이 경우를 `MULTIPLE_MAPPINGS`로 보고한다.

#### 0-2-1-1. 어떤 경우에 이 패턴을 검토할 수 있는가?

이 전제에서는 판단이 쉬워진다. 판단 기준은 두 가지다.

- **"Accelerator 화면이 어떤 Data의 커스텀 속성을 표시하고 있고, 그 Data가 표준 OCC 응답의 원천과 같은 Data인가"**
- 그리고 그 값이 **그 리소스의 속성**인가

| 조건 | 설명 |
|---|---|
| 같은 리소스 | 추가 값이 상품·장바구니·주문·사용자 등 응답 대상 리소스에 속한다 |
| 같은 시점 | 그 리소스를 조회할 때 함께 필요하다 |
| 같은 권한 | 그 API를 호출할 수 있는 사람이 봐도 되는 값이다 |
| 가벼운 계산 | 같은 요청 안에서 큰 비용 없이 구할 수 있다 |
| 화면 비종속 | 특정 화면 전용 가공값이 아니다 |

예시 (Accelerator JSP가 `${product.xxx}`, `${cartData.xxx}`로 쓰던 커스텀 속성):

| Accelerator에서 표시하던 것 | 이미 있을 Data 속성 | OCC 쪽 추가 |
|---|---|---|
| 상품 상세의 원산지·소재·인증 마크 | `ProductData.originCountry` 등 | `ProductWsDTO`에 같은 이름의 필드 |
| 상품 목록의 "신상품"·"한정" 배지 | `ProductData.badges` | `ProductWsDTO`에 필드. **검색 결과 경로의 Populator에도 있는지** 확인 |
| 장바구니 항목별 선물 포장 여부·배송 예정일 | `OrderEntryData.giftWrap` 등 | `OrderEntryWsDTO`에 필드 |
| 주문 내역의 커스텀 상태명 | `OrderHistoryData.customStatusLabel` | `OrderHistoryWsDTO`에 필드 |
| 마이페이지 회원 등급·포인트 | `CustomerData.memberGrade` | `UserWsDTO`에 필드 |

반대로 다음은 이 패턴이 맞지 않는다.

- **컨트롤러가 Model에 따로 넣던 값**(예: `model.addAttribute("relatedBrands", brandFacade.get...)`)
  - Data의 속성이 아니라 다른 facade 호출 결과다.
  - 그래서 별도 API(0-3) 또는 표준 API 조합(0-5)이 된다.
  - controller-model-analyzer의 결과에서 "Data 속성 경로"인지 "별도 Model 속성"인지로 구분할 수 있다.
- 다른 리소스 목록, 특정 화면 집계, 무거운 외부 조회, 입력에 따라 달라지는 계산 → 별도 API.

#### 0-2-1-2. 기존 API를 그대로 쓰던 기능에 영향이 있는가?

**결론**
- WsDTO와 fields만 추가하는 경우(Data·Populator가 이미 있는 경우)에는 **Accelerator 화면에 영향이 없다**.
- OCC 호출자에 대한 영향도 작다.
- Populator를 새로 추가하는 경우에는 Accelerator 화면에도 영향이 생긴다.

##### 프런트엔드 (Composable Storefront)

| 항목 | 영향 |
|---|---|
| 응답 파싱 | 모르는 필드는 무시한다. 기존 화면은 깨지지 않는다 |
| 새 필드 표시 | **자동으로 보이지 않는다.** Spartacus는 엔드포인트별 `fields`를 명시하는 경우가 많다. 그 문자열에 추가해야 한다 |
| `DEFAULT`/`FULL`을 쓰는 엔드포인트 | Spartacus 기본 설정에서 `fields=DEFAULT,...`처럼 레벨을 쓰는 엔드포인트(장바구니 등)는 레벨에 넣은 필드가 **바로 응답에 들어온다** **[버전 확인]** |
| 타입 | TypeScript 모델(`Product`, `Cart` 등)을 모듈 확장(module augmentation)으로 늘린다 |
| Normalizer | 값 변환이 필요할 때만 커스텀 Normalizer |
| 상태 캐시 | 상품은 scope(list, details 등) 단위로 캐시된다. 어느 scope의 fields에 넣을지에 따라 값이 보이는 화면이 달라진다 |
| 전송량 | DEFAULT에 무거운 필드를 넣으면 모든 페이지의 응답이 커진다 |

##### 백엔드와 다른 호출자

| 항목 | 영향 |
|---|---|
| WsDTO·fields 추가 | OCC 웹 계층에만 적용된다. Accelerator 화면에는 영향 없음 |
| **Populator 새로 추가** | `fields`는 응답에 보낼 필드만 정한다. Populator 실행 여부와는 관계가 없는 것이 일반적이다. 그래서 그 Converter를 쓰는 **모든 경로**에 비용이 붙는다. 이 전제에서는 **Accelerator 화면, 다른 OCC API, 배치, 메일**이 모두 해당한다 |
| 다른 API 소비자 | 모바일 앱·외부 연동이 같은 API를 쓰면 응답이 커진다 |
| 이름 충돌 | SAP가 이후 버전에서 같은 이름의 필드를 추가하면 충돌한다. 커스텀 필드 접두사 규칙을 검토한다 |
| 캐시 | CDN·HTTP 캐시가 있으면 무효화가 필요하다 |
| 권한 | 익명 허용 API에 민감한 필드를 넣지 않는다. Accelerator에서는 로그인 화면에서만 보이던 값이 OCC에서는 익명 API로 노출될 수 있다 |

### 0-2-2. 0-2-1 말고 다른 수정 패턴

| 패턴 | 언제 | 구현 방법 | 영향 |
|---|---|---|---|
| D. 하위 업무 로직 확장 | 계약은 같고 처리 규칙만 다르다 | Service·Strategy 빈 교체(alias), Hook 추가, facade 확장 **[버전 확인: 대상 API의 확장 지점]** | **Accelerator 화면에도 적용된다.** OCC에만 필요하면 이 패턴은 쓰지 않는다. OCC 컨트롤러 쪽에서 처리(F 또는 G)한다 |
| D'. Validator 교체·추가 | 입력 검증 규칙만 다르다 | OCC 컨트롤러가 주입받는 Validator 빈을 OCC 웹 컨텍스트에서 교체·추가 | OCC 웹 컨텍스트에서 하면 Accelerator에는 영향 없음. Accelerator 폼 Validator는 재사용할 수 없으므로 규칙을 옮겨 다시 작성 |
| E. 요청 계약 확장 | 선택 파라미터, 요청 본문 필드 추가 | 요청 WsDTO에 속성 추가 → Data로 매핑. 쿼리 파라미터는 F가 필요 | 필수로 만들면 기존 호출자가 깨진다. 반드시 선택 항목으로 |
| F. 같은 URL 재정의 | 컨트롤러 수준의 처리 변경 | 커스텀 OCC extension에 같은 매핑의 메서드 + `@RequestMappingOverride` | 원래 메서드는 호출되지 않는다. 업그레이드 시 표준 메서드의 변경을 직접 따라가야 한다. Accelerator 영향은 없음 |
| 응답 레벨만 조정 | 필드는 있으나 기본 레벨에 없다 | FieldSetLevelMapping 수정 | 0-2-1-2의 레벨 관련 영향 |
| HTTP 공통 처리 | 헤더, 로깅, 공통 검사 | OCC 웹 컨텍스트의 Filter·Interceptor | 모든 OCC 요청 |
| 오류 응답 변환 | 커스텀 예외를 특정 오류 코드로 | 예외 → 오류 DTO 변환 설정 | 프런트엔드 오류 처리에 영향 |

**이 전제에서 권하는 검토 순서**
- 순서: **C(응답 데이터) → F·G(OCC 웹 계층에서 해결) → E(요청 계약) → D(공유 계층)**
- 이전 판과 달리 D를 뒤로 미뤘다.
- facade 이하를 바꾸면 Accelerator 화면에도 퍼지기 때문이다. "삭제하지 않고 충돌 시에만 조정"하는 방침과 맞추려면, 차이는 가능한 한 OCC 웹 계층에서 흡수하는 것이 안전하다.

---

## 0-3. 완전히 새로운 OCC 엔드포인트를 작성하기

### 구성 요소

```text
[백엔드: 커스텀 OCC extension (zip의 OCC 템플릿으로 생성) [버전 확인: 2211의 템플릿명]]
  Controller (BaseController 상속, @Controller, @RequestMapping("/{baseSiteId}/..."))
    - 기존 facade를 주입받아 호출  ← 이 전제의 핵심. 새 facade는 정말 필요할 때만
    - 보안: @Secured 등, 익명 허용 여부
    - 문서: @ApiBaseSiteIdParam, @ApiFieldsParam
    - DataMapper.map(data, XxxWsDTO.class, fields)
  WsDTO + FieldSetLevelMapping
  Validator (요청 본문 검증)
  예외 처리와 오류 응답

[프런트엔드: Composable Storefront]
  endpoint 설정 → Adapter → Connector → Facade/Service (상태 관리) → 컴포넌트
```

### Accelerator 컨트롤러를 OCC 컨트롤러로 옮길 때의 변환표

이 전제의 작업 90%가 여기에 해당한다.

| Accelerator 컨트롤러의 요소 | OCC 쪽 변환 |
|---|---|
| `@RequestMapping` URL (`/cart/add`, `/my-account/...`) | REST 리소스 경로로 다시 설계 (`/{baseSiteId}/users/{userId}/carts/{cartId}/...`) |
| Form 클래스 + `@Valid` + 폼 Validator | 요청 WsDTO + OCC Validator. Validator는 스토어프런트 웹 컨텍스트에 있으므로 다시 작성 |
| `model.addAttribute(...)` | 응답 WsDTO의 필드. 여러 개라면 응답 DTO 하나로 묶거나 API를 나눈다 |
| 반환 뷰 이름, `storeCmsPageInModel` | 없음. 화면 구성은 CMS API + 프런트엔드 |
| `redirect:`, `GlobalMessages` | HTTP 상태 코드 + 오류 DTO. 메시지 표시는 프런트엔드 |
| `HttpSession` 속성 사용 | 요청 파라미터, 리소스 상태(장바구니 속성 등), 프런트엔드 상태로 대체 |
| 로그인 확인 (`@RequireHardLogIn` 등) | OAuth 토큰 + `@Secured`·접근 설정 |
| CSRF 토큰 | 불필요(토큰 인증). 대신 OAuth 클라이언트·CORS 설정 |
| 컨트롤러 안의 분기·보정 로직 | OCC 컨트롤러로 옮긴다. 여러 컨트롤러에서 반복되면 facade 추가를 검토 |
| 세션 장바구니 (`cartFacade.getSessionCart()`) | 그대로 사용 가능. OCC가 `cartId`로 세션 장바구니를 설정한다 **[버전 확인]** |

### URL 설계 기준

- **기존 리소스의 하위 리소스로 둔다**(예: `/{baseSiteId}/users/{userId}/carts/{cartId}/giftwrap`). `userId`·`cartId`의 권한 검사를 표준과 같은 방식으로 적용할 수 있다.
- 표준 경로와 겹치지 않게 한다. 겹치면 F와 구분이 모호해진다.
- 표준 규칙(`current`·`anonymous`, `fields`, `currentPage`·`pageSize`·`sort`)을 따른다.

### 장단점

| 장점 | 단점 |
|---|---|
| 기존 OCC API·Accelerator 화면에 영향이 없다 | 백엔드와 프런트엔드 양쪽에 새 코드가 생긴다 |
| 기존 facade를 그대로 부르면 구현량이 작다 | 보안·캐시·오류 처리·문서를 직접 챙겨야 한다 |
| 표준 업그레이드와 충돌이 적다 | 표준 API와 비슷한 API가 생기면 이중 관리가 된다 |

---

## 0-4. 0-2(기존 확장)와 0-3(신규 작성)의 선택 기준

### 판단 흐름

```text
Q1. 같은 리소스·같은 업무 동작인가?                ── 아니오 → 0-3 신규
     │ 예
Q2. 차이가 "응답 데이터 부족"뿐이고, 값이 Data에 이미 있는가?
                                                 ── 예 → 0-2-1 응답 확장 (C)
     │ 아니오
Q3. 차이를 OCC 웹 계층(컨트롤러)에서 흡수할 수 있는가?
     ├ 기존 계약 그대로, 처리만 다름               → F 재정의
     ├ 선택 파라미터 추가로 표현 가능              → E 요청 확장 (+F)
     └ 계약 자체가 다름                            → 0-3 신규
Q4. (Q3이 불가능할 때만) Accelerator 화면에도 같이 적용되어도 되는가?
                                                 ── 예 → D 하위 로직 확장
                                                 ── 아니오 → facade·service 추가 검토
```

### 판단 요소별 비교

| 판단 요소 | 0-2(기존 확장)이 유리 | 0-3(신규)이 유리 |
|---|---|---|
| 리소스 | 기존 리소스의 속성·동작 | 새 리소스, 여러 리소스의 조합 |
| 계약 | 기존 계약에 **추가만** 하면 된다 | 기존 계약을 바꾸거나 빼야 한다 |
| 적용 범위 | 모든 OCC 호출자에게 적용되어도 된다 | 특정 화면에만 필요하다 |
| 비용 | 추가 비용이 작다 | 무거워서 필요할 때만 호출해야 한다 |
| 권한 | 기존 API의 권한과 같다 | 다른 권한이 필요하다 |
| 원천 | 값이 표준 API가 쓰는 Data에 이미 있다 | 컨트롤러가 다른 facade를 따로 불러 Model에 넣던 값 |
| 업그레이드 | SAP가 공개한 확장 지점(fields, WsDTO, Populator)만 쓴다 | 표준 메서드를 재정의(F)해야 한다면 차라리 새 URL이 낫다 |

### 실무상 기준

- **"기존 API의 의미가 바뀌는가"로 판단한다.** 의미가 그대로이고 정보만 늘면 0-2, 의미가 바뀌거나 새 의미가 필요하면 0-3이다.
- F는 0-2와 0-3의 중간이다. **표준 메서드를 통째로 복사하게 된다면** 0-3(새 URL)을 먼저 다시 검토한다.
- 이 전제에서는 "facade 이하를 건드리지 않고 끝나는가"도 중요한 기준이다. 0-2든 0-3이든 OCC 웹 계층에서 끝나는 쪽을 우선한다.

---

## 0-5. 그 밖의 이행 방법

### 5-1. 표준 OCC API 여러 개를 조합하기

조합은 가능하다. 어디서 조합하느냐에 따라 두 가지가 있다.

| 방식 | 내용 | 적합 | 부적합 |
|---|---|---|---|
| **프런트엔드 조합** | Spartacus에서 표준 API를 여러 번 호출해 한 화면 구성. Spartacus 표준 화면도 이 방식이다 | **조회**, 각 호출이 독립적, 일부 실패해도 표시 가능 | **원자성이 필요한 갱신**, 호출 횟수가 많아 성능 문제 |
| **백엔드 조합** | 신규 OCC 엔드포인트에서 기존 facade 여러 개를 호출 | 순서·트랜잭션 보장, 호출 감소 | 결국 0-3이다. 다만 이 전제에서는 facade 재사용이라 비용이 작다 |

- 예: "장바구니 담기 후 배송 방법 설정"처럼 중간 실패 시 되돌릴 필요가 없으면 프런트엔드 순차 호출로 충분하다.
- 예: "포인트 사용과 주문 확정"처럼 한쪽만 성공하면 안 되면 백엔드에서 처리해야 한다.
- **Accelerator 페이지 컨트롤러가 facade 여러 개를 불러 Model을 채우던 경우**가 대표적인 조합 후보다. 각 facade에 대응하는 표준 API가 있으면 프런트엔드 조합으로, 없으면 그 부분만 0-3으로 한다.

### 5-2. CMS로 옮기기 (가져온 DB의 CMS 데이터 활용)

- DB를 그대로 가져오므로 Accelerator의 콘텐츠 카탈로그(페이지, 슬롯, 컴포넌트)가 이미 있다.
- Composable Storefront는 CMS 페이지 API로 이를 읽는다. 표준 컴포넌트 타입은 Spartacus에 매핑이 있는 것이 많다.
- **커스텀 CMS 컴포넌트**는 Angular 컴포넌트 매핑을 추가하면 된다. 속성은 CMS API 응답에 실리므로 새 REST 엔드포인트가 필요 없는 경우가 있다. **[버전 확인: 커스텀 컴포넌트 속성이 응답에 실리는 조건]**
- 다만 Accelerator용 페이지 템플릿·슬롯 구성은 Spartacus의 레이아웃 설정과 맞춰야 한다. 그래서 CMS 데이터를 조정하는 작업은 별도로 필요하다(Spartacus 샘플 데이터와 구조가 다르다).
- CMS 컴포넌트 컨트롤러(Accelerator의 `*ComponentController`)가 하던 데이터 조회는 OCC API나 컴포넌트 속성으로 옮긴다(루트의 `sap-commerce-cms-component-controller-migration-review.md` 참고).

### 5-3. 프런트엔드만으로 처리

- 표시 형식, 정렬, 필터링, 클라이언트 검증, 화면 이동.
- **보안·가격·재고 판정은 옮기지 않는다.** 서버에서 다시 검증해야 한다.

### 5-4. zip에 포함된 OCC extension과 Spartacus 기능 라이브러리

- B2B, 주문 승인, ASM 등은 OCC extension과 Spartacus 기능 라이브러리가 짝으로 제공된다.
- 전제대로 zip에 있으므로, 커스텀 개발 전에 **활성화만으로 되는지** 확인한다.

### 5-5. 검색 결과 확장은 Solr 쪽에서

- 상품 목록·검색 결과는 상품 상세와 다른 Converter를 탄다.
- 그래서 Accelerator 검색 결과 화면에 커스텀 값이 나왔다면, 그 값은 이미 **색인 속성과 검색 결과 Populator**로 구현되어 있을 것이다. OCC 검색 응답의 WsDTO에도 같은 필드가 필요하다.

### 5-6. Commerce 밖의 조합 계층 (BFF 등)

여러 시스템을 합칠 때 쓸 수 있으나, 이 전제("같은 백엔드에 OCC를 얹는다")와 방향이 달라 우선순위는 낮다.

### 5-7. 이행하지 않기

Accelerator 전용 동작(JSP 조각 반환 AJAX, 세션 기반 임시 처리, 쓰지 않는 화면)은 대상에서 뺀다. 코드는 삭제하지 않으므로 "이행 불필요"로 판정 결과만 기록한다.

---

## 정리: 컨트롤러 메서드 하나를 판정하는 순서

```text
1. 업무 기능으로 분해 (페이지 컨트롤러는 1:N)
2. 메서드 안의 요소를 분류
   - facade 호출         → 재사용 대상
   - Model 속성          → Data 속성 경로인지, 별도 facade 결과인지
   - 컨트롤러 로직       → OCC 컨트롤러로 이전 대상
   - 뷰·CMS·리다이렉트   → 프런트엔드·CMS
3. 기능마다 판정
   - 콘텐츠 → CMS (5-2) / 서버 불필요 → 프런트엔드 (5-3)·이행 불필요 (5-7)
   - 표준 OCC(zip의 extension 포함)로 충족 → 대체 (0-1)
   - 표준 여러 개로 충족 → 조합 (5-1)
   - Data에 있는 값이 응답에 없음 → 응답 확장 (0-2-1)
   - 컨트롤러 수준 차이 → 재정의 (F) / 요청 확장 (E)
   - 새 의미 → 신규 엔드포인트 (0-3)
   - 위로 안 되고 공유 계층 변경이 필요 → D (Accelerator 영향 확인)
4. 재사용할 빈이 코어 쪽 컨텍스트에 있는지 확인
```

### 이 저장소의 도구와의 연결

| 단계 | 쓸 수 있는 산출물 |
|---|---|
| 1. 대상 컨트롤러 목록 | staticAnalyzer `controller-endpoints.csv` (`controller_family=accelerator`) |
| 2. facade 호출, 컨트롤러 로직 | staticAnalyzer `controller-call-edges.csv` |
| 2. Model 속성 | controller-model-analyzer |
| 3. 표준 OCC 응답과 fields | occ-response-analyzer |
| 3. 대응 판정 방법 | 루트의 `accelerator-model-vs-occ-response-comparison.md` |
| 4. 빈의 컨텍스트·최종 빈 | spring-bean-analyzer-from-xml |
| 활성 extension | extension-analyzer |

---

## 추가 논의가 필요해 보이는 부분

1. **Accelerator 스토어프런트의 운영 여부**
   - 파일은 남기는데, 스토어프런트 extension을 계속 활성화해서 운영하는가? 아니면 OCC 전환 뒤 비활성화하는가?
   - 운영한다면 D와 Populator 추가의 영향이 실제로 생긴다.
2. **스토어프런트 웹 컨텍스트에만 있는 빈·클래스의 처리**
   - 폼 Validator, 헬퍼 등을 OCC extension에 다시 만들지, 공용 extension으로 옮길지 정해야 한다.
   - 옮기는 쪽은 "충돌 시에만 조정" 원칙과 부딪힐 수 있다.
3. **CMS 데이터의 조정 범위**
   - 가져온 Accelerator 콘텐츠 카탈로그를 Spartacus 레이아웃에 맞추는 작업을 이행 범위에 넣는가?
4. **F(`@RequestMappingOverride`)의 허용 범위**와 **커스텀 필드 명명 규칙**.
5. **[버전 확인] 항목의 실제 확인**
   - FieldSetLevelMapping 병합, Spartacus 기본 fields
   - ProductOption과 Populator의 관계
   - OCC의 세션 장바구니 설정, 2211의 OCC 템플릿명

## 참고 (SAP 공식 문서, 대상 버전에 맞춰 확인)

Extending the REST API(`@RequestMappingOverride`), Fields Configuration Format, Data Mapping Mechanism, Converters and Populators, Composable Storefront의 Extending Built-in Models·Connecting to Other Systems·Configuring Endpoints·CMS Components. 링크는 루트의 `sap-commerce-composable-storefront-migration-discussion.md` 7장에 있다.
