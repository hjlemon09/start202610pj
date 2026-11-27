# [S0] Accelerator 엔드포인트를 OCC 엔드포인트로 옮길 때의 이행 패턴 검토 (축약본)

---

## [S2] 이행 작업에서 나타나는 패턴의 전체 그림

### [S2.1] Accelerator 엔드포인트의 종류와 이행 단위

[S2.1-T1] (표)

| Accelerator 쪽 종류 | OCC 쪽 이행 단위 |
|---|---|
| 페이지 컨트롤러 (JSP 뷰 반환) | **1:N**. CMS 페이지 API + 여러 OCC API + 프런트엔드 처리로 분해 |
| AJAX/JSON 컨트롤러 (`@ResponseBody`) | **1:1에 가깝다**. 대응 OCC API를 찾거나 새로 만든다 |
| 폼 POST + 리다이렉트 | 처리는 OCC의 POST/PUT/PATCH, 화면 이동은 프런트엔드 라우팅 |

### [S2.2] 패턴 목록

[S2.2-L1] 엔드포인트를 업무 기능(조회 하나, 갱신 하나) 단위로 쪼갠 뒤, 기능마다 아래 패턴 중 하나 이상을 고른다.

[S2.2-T1] (표)

| 패턴 | 요약 | 주 작업 |
|---|---|---|
| A. 표준 API로 대체 | 표준 OCC + Spartacus 표준 기능 | 없음(필요한 OCC extension 활성화) |
| B. 표준 API 조합 | 표준 API 여러 개를 프런트엔드에서 조합 | 프런트엔드만 |
| C. 응답 데이터 확장 | 같은 URL의 응답에 필드 추가 | WsDTO + fields 매핑. Data·Populator는 대개 이미 있음 |
| D. 하위 업무 로직 확장 | 같은 계약, 처리 규칙만 변경 | Service·Strategy·Hook |
| E. 요청 계약 확장 | 선택 파라미터, 요청 DTO 필드 추가 | WsDTO, 컨트롤러 재정의 |
| F. 같은 URL 재정의 | `@RequestMappingOverride`로 처리 교체 | 커스텀 OCC 컨트롤러 |
| G. 신규 엔드포인트 | 새 URL, 새 계약 | 커스텀 OCC 컨트롤러 + 기존 facade 호출 |
| H. API가 필요 없음 | CMS, 프런트엔드, 설정 | CMS 컴포넌트 매핑 |

[S2.2-L2] facade·service를 재사용하는 이행에서는 **G가 가장 많이 나올 가능성이 높다.** 표준 OCC에 없는 기능은 대부분 "기존 facade를 부르는 새 OCC 컨트롤러"가 된다. C와 F가 그 다음이다. D는 업무 규칙 자체가 바뀌는 경우에 쓴다.

---

## [S3] 기존 OCC API로 완전히 대체할 수 있는 경우

### [S3.1] 대체 가능 판정 조건 (모두 만족)

1. [S3.1-L1] **입력**: Accelerator가 받던 파라미터를 OCC API의 경로·쿼리·본문으로 표현할 수 있다.
2. [S3.1-L2] **처리**: 호출되는 facade/service가 같거나 업무상 같은 결과를 낸다. 부수 효과(이벤트, 메일, 감사 로그)도 포함한다.
   - [S3.1-L3] facade를 그대로 재사용하므로 **같은 facade를 부르는지**를 호출 그래프로 비교하면 판정이 빠르다.
3. [S3.1-L4] **출력**: 화면에 필요한 값이 OCC 응답(fields 지정 포함)에 모두 있다.
4. [S3.1-L5] **조건**: 권한, 로그인 여부, 사이트·통화·언어 컨텍스트가 같은 방식으로 적용된다.

### [S3.2] 주의할 차이

- [S3.2-L1] **컨트롤러 안의 로직:** facade 호출 전후의 처리(값 보정, 조건 분기, 추가 facade 호출)가 있으면 "같은 facade를 부른다"만으로는 대체가 아니다.
- [S3.2-L2] **세션 의존:** Accelerator 컨트롤러가 HTTP 세션에 넣던 커스텀 값은 OCC에 없다.
- [S3.2-L3] **fields 차이:** "값이 없다"고 판정하기 전에 `FULL`이나 명시 지정으로 다시 확인한다(occ-response-analyzer의 `occ-fieldset-mappings.csv`).
- [S3.2-L4] **OCC extension 미활성:** B2B 등은 별도 OCC extension(예: `b2bocc`)을 켜야 API가 생긴다.

### [S3.3] 이행 작업

[S3.3-L1] 백엔드는 없음 또는 OCC extension 활성화, 프런트엔드는 Spartacus 표준 기능 + 필요하면 endpoint `fields` 조정.

---

## [S4] 기존 OCC API를 일부 수정하는 경우

### [S4.1] 같은 엔드포인트에 응답 항목을 추가하기

#### [S4.1.1] 구현 순서 (기존 Data·Populator를 재사용하는 경우)

[S4.1.1-L1] (코드 블록)
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

- [S4.1.1-L2] 1·2가 **OCC가 부르는 Converter에도 등록되어 있는지** 확인한다.
  - [S4.1.1-L3] Accelerator 컨트롤러는 다른 facade 메서드나 다른 ProductOption으로 같은 Data를 채웠을 수 있다.
  - [S4.1.1-L4] 예: 상품은 `getProductForCodeAndOptions(code, options)`에 넘기는 `ProductOption`에 따라 실행되는 Populator가 달라진다. OCC 상품 API가 어떤 옵션을 넘기는지는 **[미확인]**이다.
- [S4.1.1-L5] 1·2가 **스토어프런트나 addon extension에 정의되어 있으면** 그 extension을 비활성화할 때 함께 없어진다(스토어프런트의 beans.xml·spring.xml 등). 이 경우 core·facades extension으로 옮긴다.
- [S4.1.1-L6] 4를 하지 않아도 `fields=...,originCountry`처럼 명시 지정하면 응답에 나온다. 레벨에 넣는 것은 "레벨로만 fields를 지정하는 호출자에게도 기본으로 보일지"를 정하는 일이다.
- [S4.1.1-L7] FieldSetLevelMapping 빈(`parent="fieldSetLevelMapping"`, `dtoClass`, `levelMapping`)은 **OCC 웹 컨텍스트에 정의해야 한다**. `DefaultFieldSetBuilder`가 같은 컨텍스트에서 읽기 때문이다.
- [S4.1.1-L8] 같은 `dtoClass`에 빈을 하나 더 정의하면 기존 정의와 합쳐지는지 덮어쓰는지는 **[미확인]**이다. `DefaultFieldSetLevelHelper.addToLevelMap`의 동작을 소스로 확인한다. occ-response-analyzer는 이 경우를 `MULTIPLE_MAPPINGS`로 보고한다.

#### [S4.1.2] 응답 항목 추가를 검토할 수 있는 경우

[S4.1.2-L1] Accelerator 화면용 Data가 이미 있으므로 판단이 쉬워진다. 판단 기준은 두 가지다.

- [S4.1.2-L2] **"Accelerator 화면이 어떤 Data의 커스텀 속성을 표시하고 있고, 그 Data가 표준 OCC 응답의 원천과 같은 Data인가"**
- [S4.1.2-L3] 그리고 그 값이 **그 리소스의 속성**인가

[S4.1.2-T1] (표)

| 조건 | 설명 |
|---|---|
| 같은 리소스 | 추가 값이 상품·장바구니·주문·사용자 등 응답 대상 리소스에 속한다 |
| 같은 시점 | 그 리소스를 조회할 때 함께 필요하다 |
| 같은 권한 | 그 API를 호출할 수 있는 사람이 봐도 되는 값이다 |
| 가벼운 계산 | 같은 요청 안에서 큰 비용 없이 구할 수 있다 |
| 화면 비종속 | 특정 화면 전용 가공값이 아니다 |

[S4.1.2-L4] 예시 (Accelerator JSP가 `${product.xxx}`, `${cartData.xxx}`로 쓰던 커스텀 속성):

[S4.1.2-T2] (표)

| Accelerator에서 표시하던 것 | 이미 있을 Data 속성 | OCC 쪽 추가 |
|---|---|---|
| 상품 상세의 원산지·소재·인증 마크 | `ProductData.originCountry` 등 | `ProductWsDTO`에 같은 이름의 필드 |
| 상품 목록의 "신상품"·"한정" 배지 | `ProductData.badges` | `ProductWsDTO`에 필드. **검색 결과 경로의 Populator에도 있는지** 확인 |
| 장바구니 항목별 선물 포장 여부·배송 예정일 | `OrderEntryData.giftWrap` 등 | `OrderEntryWsDTO`에 필드. Spartacus는 `entries(...)`를 명시 목록으로 요청하므로 **cart endpoint 설정에도 추가**해야 보인다 |
| 주문 내역의 커스텀 상태명 | `OrderHistoryData.customStatusLabel` | `OrderHistoryWsDTO`에 필드 |
| 마이페이지 회원 등급·포인트 | `CustomerData.memberGrade` | `UserWsDTO`에 필드 |

[S4.1.2-L5] 반대로 다음은 이 패턴이 맞지 않는다.

- [S4.1.2-L6] **컨트롤러가 Model에 따로 넣던 값**(예: `model.addAttribute("relatedBrands", brandFacade.get...)`)
  - [S4.1.2-L7] Data의 속성이 아니라 다른 facade 호출 결과다.
  - [S4.1.2-L8] 그래서 별도 API(신규 엔드포인트) 또는 표준 API 조합이 된다.
  - [S4.1.2-L9] controller-model-analyzer의 결과에서 "Data 속성 경로"인지 "별도 Model 속성"인지로 구분할 수 있다.
- [S4.1.2-L10] 다른 리소스 목록, 특정 화면 집계, 무거운 외부 조회, 입력에 따라 달라지는 계산 → 별도 API.

### [S4.2] 응답 항목 추가 외의 수정 패턴

[S4.2-T1] (표)

| 패턴 | 언제 | 구현 방법 | 영향 |
|---|---|---|---|
| D. 하위 업무 로직 확장 | 계약은 같고 처리 규칙만 다르다 | Service·Strategy 빈 교체(alias), Hook 추가, facade 확장 (대상 API에 어떤 확장 지점이 있는지는 **[미확인]**) | 같은 계층을 쓰는 모든 채널(배치, 메일, 백오피스)에 적용된다. 업무 규칙 자체의 변경이면 이것이 맞는 위치다. OCC 요청에만 필요한 처리면 OCC 컨트롤러 쪽(F 또는 G)에서 한다 |
| D'. Validator 교체·추가 | 입력 검증 규칙만 다르다 | OCC 컨트롤러가 주입받는 Validator 빈을 OCC 웹 컨텍스트에서 교체·추가 | OCC 요청에만 적용. Accelerator 폼 Validator는 재사용할 수 없고 Accelerator extension 비활성화로 없어지므로, 규칙을 옮겨 다시 작성 |
| E. 요청 계약 확장 | 선택 파라미터, 요청 본문 필드 추가 | 요청 WsDTO에 속성 추가 → Data로 매핑. 쿼리 파라미터는 F가 필요 | 필수로 만들면 기존 호출자가 깨진다. 반드시 선택 항목으로 |
| F. 같은 URL 재정의 | 컨트롤러 수준의 처리 변경 | 커스텀 OCC extension에 **완전히 같은** 매핑의 메서드 + `@RequestMappingOverride`(자세한 내용은 같은 폴더의 `occ-request-mapping-override.md`) | 원래 메서드는 호출되지 않는다. 업그레이드 시 표준 메서드의 변경을 직접 따라가야 한다 |
| 응답 레벨만 조정 | 필드는 있으나 기본 레벨에 없다 | FieldSetLevelMapping 수정 | fields를 레벨(DEFAULT/FULL)로 지정하는 호출자의 응답에 필드가 늘어난다 |
| HTTP 공통 처리 | 헤더, 로깅, 공통 검사 | OCC 웹 컨텍스트의 Filter·Interceptor | 모든 OCC 요청 |
| 오류 응답 변환 | 커스텀 예외를 특정 오류 코드로 | 예외 → 오류 DTO 변환 설정 | 프런트엔드 오류 처리에 영향 |

[S4.2-L13] **권하는 검토 순서**
- [S4.2-L14] 먼저 **C(응답 데이터)**로 되는지 본다.
- [S4.2-L15] 남은 차이는 성격으로 나눈다.
  - [S4.2-L16] **업무 규칙 자체**(검증·계산·후처리)의 차이 → **D(공유 계층)**. 규칙은 facade·service에 하나만 두는 쪽이 이중 관리를 피한다.
  - [S4.2-L17] **OCC 요청 처리 방식**(파라미터 해석, 응답 구성)의 차이 → **F·G·E(OCC 웹 계층)**.
- [S4.2-L18] D를 쓰는 경우에는 다른 채널(배치, 메일, 백오피스) 영향을 확인한다.

---

## [S5] 완전히 새로운 OCC 엔드포인트를 작성하기

### [S5.1] 구성 요소

[S5.1-L1] (코드 블록)
```text
[백엔드: 커스텀 OCC extension]
  ant extgen의 yocc 템플릿으로 생성. 이름은 occ로 끝나야 한다(예: xyzocc)
  2211부터 OCC는 addon이 아니라 extension 방식이다
  Controller (BaseController 상속, <ext>.controllers 패키지, @RequestMapping("/{baseSiteId}/..."))
    - 기존 facade를 주입받아 호출  ← 핵심. 새 facade는 정말 필요할 때만
    - 보안: @Secured({"ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", ...}) 등, 익명 허용 여부
    - 문서: @ApiBaseSiteIdParam, @ApiFieldsParam
    - DataMapper.map(data, XxxWsDTO.class, fields)
  WsDTO + FieldSetLevelMapping
  Validator (요청 본문 검증)
  예외 처리와 오류 응답

[프런트엔드: Composable Storefront]
  endpoint 설정 → Adapter → Connector → Facade/Service (상태 관리) → 컴포넌트
```

### [S5.2] Accelerator 컨트롤러를 OCC 컨트롤러로 옮길 때의 변환표

[S5.2-L1] 이행 작업의 대부분(약 90%)이 여기에 해당한다.

[S5.2-T1] (표)

| Accelerator 컨트롤러의 요소 | OCC 쪽 변환 |
|---|---|
| `@RequestMapping` URL (`/cart/add`, `/my-account/...`) | REST 리소스 경로로 다시 설계 (`/{baseSiteId}/users/{userId}/carts/{cartId}/...`) |
| Form 클래스 + `@Valid` + 폼 Validator | 요청 WsDTO + OCC Validator. Validator는 스토어프런트 웹 컨텍스트에 있고 Accelerator extension 비활성화로 없어지므로 OCC extension에 다시 작성 |
| 스토어프런트 전용 헬퍼·strategy·util | Accelerator extension 비활성화로 없어진다. OCC에 필요한 것은 OCC extension으로 옮긴다 |
| `model.addAttribute(...)` | 응답 WsDTO의 필드. 여러 개라면 응답 DTO 하나로 묶거나 API를 나눈다 |
| 반환 뷰 이름, `storeCmsPageInModel` | 없음. 화면 구성은 CMS API + 프런트엔드 |
| `redirect:`, `GlobalMessages` | HTTP 상태 코드 + 오류 DTO. 메시지 표시는 프런트엔드 |
| `HttpSession` 속성 사용 | 요청 파라미터, 리소스 상태(장바구니 속성 등), 프런트엔드 상태로 대체 |
| 로그인 확인 (`@RequireHardLogIn` 등) | OAuth 토큰 + `@Secured`·접근 설정 |
| CSRF 토큰 | 불필요(토큰 인증). 대신 OAuth 클라이언트·CORS 설정 |
| 컨트롤러 안의 분기·보정 로직 | OCC 컨트롤러로 옮긴다. 여러 컨트롤러에서 반복되면 facade 추가를 검토 |
| 세션 장바구니 (`cartFacade.getSessionCart()`) | 그대로 사용 가능. OCC의 `CartMatchingFilter`가 경로의 `cartId`로 세션 장바구니를 설정한다 |
| 커스텀 MVC 인터셉터 | 스토어프런트의 인터셉터는 옮겨지지 않는다. 2211에서는 `commercewebservices` 웹 컨텍스트를 쓰므로, 인터셉터는 OCC extension의 web-spring.xml에서 다시 등록한다 |

### [S5.3] URL 설계 기준

- [S5.3-L1] **기존 리소스의 하위 리소스로 둔다**(예: `/{baseSiteId}/users/{userId}/carts/{cartId}/giftwrap`). 표준 경로와 겹치지 않게 하고, 표준 규칙(`current`·`anonymous`, `fields`, `currentPage`·`pageSize`·`sort`)을 따른다.

---

## [S6] 기존 API 확장과 신규 엔드포인트 작성의 선택 기준

### [S6.1] 판단 흐름

[S6.1-L1] (코드 블록)
```text
Q1. 같은 리소스·같은 업무 동작인가?                ── 아니오 → 신규 엔드포인트
     │ 예
Q2. 차이가 "응답 데이터 부족"뿐이고, 값이 Data에 이미 있는가?
                                                 ── 예 → 응답 데이터 확장 (C)
     │ 아니오
Q3. 차이가 업무 규칙(검증·계산·후처리) 자체인가?
     ├ 예, 다른 채널(배치·메일·백오피스)에도 같은 규칙이 맞음 → D 하위 로직 확장
     ├ 예, OCC 요청에만 맞음                       → F 재정의 또는 facade·service 추가 검토
     │ 아니오 (요청 처리 방식의 차이)
Q4. OCC 웹 계층(컨트롤러)에서 흡수할 수 있는가?
     ├ 기존 계약 그대로, 처리만 다름               → F 재정의
     ├ 선택 파라미터 추가로 표현 가능              → E 요청 확장 (+F)
     └ 계약 자체가 다름                            → 신규 엔드포인트
```

### [S6.2] 판단 요소별 비교

[S6.2-T1] (표)

| 판단 요소 | 기존 API 확장이 유리 | 신규 엔드포인트가 유리 |
|---|---|---|
| 리소스 | 기존 리소스의 속성·동작 | 새 리소스, 여러 리소스의 조합 |
| 계약 | 기존 계약에 **추가만** 하면 된다 | 기존 계약을 바꾸거나 빼야 한다 |
| 적용 범위 | 모든 OCC 호출자에게 적용되어도 된다 | 특정 화면에만 필요하다 |
| 비용 | 추가 비용이 작다 | 무거워서 필요할 때만 호출해야 한다 |
| 권한 | 기존 API의 권한과 같다 | 다른 권한이 필요하다 |
| 원천 | 값이 표준 API가 쓰는 Data에 이미 있다 | 컨트롤러가 다른 facade를 따로 불러 Model에 넣던 값 |
| 업그레이드 | SAP가 공개한 확장 지점(fields, WsDTO, Populator)만 쓴다 | 표준 메서드를 재정의(F)해야 한다면 차라리 새 URL이 낫다 |

### [S6.3] 실무상 기준

- [S6.3-L1] **"기존 API의 의미가 바뀌는가"로 판단한다.** 의미가 그대로이고 정보만 늘면 기존 API 확장, 의미가 바뀌거나 새 의미가 필요하면 신규 엔드포인트다.
- [S6.3-L2] F는 기존 API 확장과 신규 엔드포인트의 중간이다. 표준 메서드를 통째로 복사하게 되거나 매핑을 완전히 맞추기 어렵다면 신규 엔드포인트(새 URL)를 먼저 검토한다.
- [S6.3-L3] 업무 규칙은 공유 계층, OCC 요청 처리 방식은 OCC 웹 계층으로 나눈다.

---

## [S7] 그 밖의 이행 방법

### [S7.1] 표준 OCC API 여러 개를 조합하기

[S7.1-L1] 조합은 가능하다. 어디서 조합하느냐에 따라 두 가지가 있다.

[S7.1-T1] (표)

| 방식 | 내용 | 적합 | 부적합 |
|---|---|---|---|
| **프런트엔드 조합** | Spartacus에서 표준 API를 여러 번 호출해 한 화면 구성. Spartacus 표준 화면도 이 방식이다 | **조회**, 각 호출이 독립적, 일부 실패해도 표시 가능 | **원자성이 필요한 갱신**, 호출 횟수가 많아 성능 문제 |
| **백엔드 조합** | 신규 OCC 엔드포인트에서 기존 facade 여러 개를 호출 | 순서·트랜잭션 보장, 호출 감소 | 결국 신규 엔드포인트 작성이다. 다만 기존 facade를 재사용하면 비용이 작다 |

- [S7.1-L2] 예: "장바구니 담기 후 배송 방법 설정"처럼 중간 실패 시 되돌릴 필요가 없으면 프런트엔드 순차 호출로 충분하다.
- [S7.1-L3] 예: "포인트 사용과 주문 확정"처럼 한쪽만 성공하면 안 되면 백엔드에서 처리해야 한다.
- [S7.1-L4] **Accelerator 페이지 컨트롤러가 facade 여러 개를 불러 Model을 채우던 경우**가 대표적인 조합 후보다. 각 facade에 대응하는 표준 API가 있으면 프런트엔드 조합으로, 없으면 그 부분만 신규 엔드포인트로 한다.
