# Accelerator 엔드포인트를 OCC 엔드포인트로 옮길 때의 이행 패턴 검토

작성일: 2026-11-27

## 질문

Accelerator에서 Composable Storefront로 이행할 때, Accelerator의 엔드포인트를 OCC 엔드포인트로 바꿔야 한다. 이때 OCC API를 새로 만드는 경우와 기존 OCC API를 확장하는 경우의 이행 방법을 조사하고 검토한다.

- 0. 어떤 패턴이 나타날 수 있는가?
  - 0-1. 기존 OCC API로 완전히 대체할 수 있는 경우
  - 0-2. 기존 OCC API를 일부 수정하는 경우
    - 0-2-1. 같은 엔드포인트를 쓰되 응답에 항목을 추가하는 경우
      - 0-2-1-1. 어떤 경우에 이 패턴을 검토할 수 있는가?
      - 0-2-1-2. 원래 API를 그대로 쓰던 기능, 특히 프런트엔드에 영향이 있는가?
    - 0-2-2. 0-2-1 말고 다른 수정 패턴이 있는가?
  - 0-3. 완전히 새로운 OCC 엔드포인트를 작성하는 경우
- 0-4. 0-2와 0-3 중 어느 쪽이 적절한지 무엇을 기준으로 판단하는가?
- 0-5. 이 밖에 다른 이행 방법이 있는가? (표준 OCC API 두 개를 조합하는 방법 등)

이전 논의(`sap-commerce-composable-storefront-migration-discussion.md`)의 분류를 바탕으로, 질문 번호에 맞춰 정리했다. 버전에 따라 동작이 다를 수 있는 부분은 **[버전 확인]**으로 표시했다. 대상 버전(2211 등)의 실제 소스와 설정으로 확인해야 한다.

---

## 0. 이행 작업에서 나타나는 패턴의 전체 그림

먼저 Accelerator 엔드포인트는 두 종류로 나뉜다. 종류에 따라 이행 단위가 달라진다.

| Accelerator 쪽 종류 | 특징 | OCC 쪽 이행 단위 |
|---|---|---|
| 페이지 컨트롤러 (JSP 뷰 반환) | Model 속성 여러 개 + CMS 페이지 구성 | **1:N**. CMS 페이지 API + 여러 OCC API + 프런트엔드 처리로 분해 |
| AJAX/JSON 컨트롤러 (`@ResponseBody`) | 이미 데이터 API에 가까움 | **1:1에 가까움**. 대응 OCC API를 찾거나 새로 만듦 |
| 폼 POST + 리다이렉트 | 처리 후 다음 페이지로 이동 | 처리는 OCC의 POST/PUT/PATCH, 화면 이동은 프런트엔드 라우팅 |

따라서 "엔드포인트 하나 → OCC 엔드포인트 하나"로 생각하면 판단이 어긋나기 쉽다. **업무 기능(조회 하나, 갱신 하나) 단위로 쪼갠 뒤**, 기능마다 아래 패턴 가운데 하나 이상을 고른다.

| 패턴 | 요약 | 백엔드 변경 | 프런트엔드 변경 |
|---|---|---|---|
| A. 표준 API로 대체 (0-1) | 표준 OCC + Spartacus 표준 기능 | 없음 | 설정 정도 |
| B. 표준 API 조합 (0-5) | 표준 API 여러 개를 프런트엔드에서 조합 | 없음 | 있음 |
| C. 응답 데이터 확장 (0-2-1) | 같은 URL의 응답에 필드 추가 | Data/WsDTO/Populator/fields | 모델 타입과 fields 설정 |
| D. 하위 업무 로직 확장 (0-2-2) | 같은 URL, 같은 계약, 처리만 변경 | Service/Strategy/Hook | 대개 없음 |
| E. 요청 계약 확장 (0-2-2) | 선택 파라미터, 요청 DTO 필드 추가 | WsDTO, 컨트롤러 재정의 | 요청 측 수정 |
| F. 같은 URL 재정의 (0-2-2) | `@RequestMappingOverride`로 처리를 바꿔 끼움 | 커스텀 컨트롤러 | 대개 없음 |
| G. 신규 엔드포인트 (0-3) | 새 URL, 새 계약 | 컨트롤러~Service | Adapter/Connector 등 |
| H. API가 필요 없음 (0-5) | CMS, 프런트엔드, 설정으로 처리 | 없음 또는 CMS 타입 | 컴포넌트 |

하나의 업무 기능에 여러 패턴이 겹칠 수 있다. 예를 들어 상품 상세는 A + C + H(CMS)처럼 이행되는 것이 보통이다.

---

## 0-1. 기존 OCC API로 완전히 대체할 수 있는 경우

### 대체 가능 판정 조건

다음을 **모두** 만족하면 대체로 본다.

1. **입력**: Accelerator가 받던 파라미터를 OCC API의 경로·쿼리·본문으로 표현할 수 있다.
2. **처리**: 호출되는 Facade/Service가 같거나, 업무상 같은 결과를 낸다. 부수 효과(세션 변경, 이벤트, 메일, 감사 로그)도 포함해 확인한다.
3. **출력**: 화면에 필요한 값이 OCC 응답(fields 지정 포함)에 모두 있다.
4. **조건**: 권한, 로그인 여부, 사이트·통화·언어 컨텍스트가 같은 방식으로 적용된다.

### 주의할 차이

- **세션 의존**: Accelerator는 HTTP 세션에 장바구니·사용자·최근 본 상품 등을 둔다. OCC는 무상태이므로 `userId`·`cartId`를 매번 경로로 넘긴다. 세션에 숨어 있던 값이 있으면 그것을 어디서 얻을지 정해야 한다.
- **fields 차이**: OCC는 `fields` 파라미터로 응답을 줄인다. "값이 없다"고 판정하기 전에 `FULL` 또는 명시 필드로 다시 확인한다. (occ-response-analyzer의 `occ-fieldset-mappings.csv`로 레벨별 필드를 볼 수 있다.)
- **표준 OCC extension 미활성**: B2B, 위시리스트 등은 별도 OCC extension(예: `b2bocc`)을 켜야 API가 생긴다. "표준 API가 없다"고 판단하기 전에 SAP 제공 extension을 확인한다.

### 이행 작업

- 백엔드: 없음, 또는 필요한 OCC extension 활성화.
- 프런트엔드: Spartacus 표준 컴포넌트·서비스를 그대로 쓴다. 필요하면 endpoint 설정의 `fields`만 조정한다.

---

## 0-2. 기존 OCC API를 일부 수정하는 경우

### 0-2-1. 같은 엔드포인트에 응답 항목을 추가하기

#### 구현 순서 (일반적인 형태)

```text
1. *Data 빈에 속성 추가            (*-beans.xml, 예: ProductData.originCountry)
2. Populator 작성·등록              (기존 Converter의 populators 목록에 추가)
3. *WsDTO 빈에 같은 이름의 속성 추가 (OCC 쪽 beans.xml)
     → DataMapper(Orika)가 이름이 같으면 자동 매핑. 이름·타입이 다르면 매핑 설정 필요
4. FieldSetLevelMapping에 필드 추가 (BASIC / DEFAULT / FULL 중 어디에 넣을지 결정)
5. Spartacus: 모델 타입 확장 + endpoint fields 설정 + 표시 컴포넌트
```

- 4를 하지 않아도 `fields=...,originCountry`처럼 **명시 지정하면 응답에 나온다**. 레벨에 넣는 것은 "fields를 레벨로만 지정하는 호출자에게도 기본으로 보일지"를 정하는 일이다.
- 같은 `dtoClass`에 FieldSetLevelMapping 빈을 하나 더 정의하면 기존 정의와 합쳐지는 방식이 쓰인다. **[버전 확인]** (occ-response-analyzer는 이 경우를 `MULTIPLE_MAPPINGS`로 보고한다.)

#### 0-2-1-1. 어떤 경우에 이 패턴을 검토할 수 있는가?

판단 기준은 **"추가하려는 값이 그 리소스의 속성인가"**이다. 다음 조건을 만족할수록 이 패턴이 맞다.

| 조건 | 설명 |
|---|---|
| 같은 리소스 | 추가 값이 상품·장바구니·주문·사용자 등 응답 대상 리소스 자체에 속한다 |
| 같은 시점 | 그 리소스를 조회할 때 함께 필요하다 (다른 버튼을 눌렀을 때가 아님) |
| 같은 권한 | 그 API를 호출할 수 있는 사람이면 볼 수 있어도 되는 값이다 |
| 가벼운 계산 | 같은 요청 안에서 큰 비용 없이 구할 수 있다 |
| 화면 비종속 | 특정 화면 전용 가공값이 아니라 다른 화면·채널에서도 의미가 있다 |

예시 (Accelerator에서 Model 속성이나 JSP에서 쓰던 값):

| Accelerator에서 하던 것 | OCC 확장 예 |
|---|---|
| 상품 상세에 원산지·소재·인증 마크 표시 (커스텀 Model 속성) | `ProductWsDTO`에 `originCountry`, `certifications` 추가 |
| 상품 목록에 "신상품"·"한정" 배지 | `ProductWsDTO`에 `badges` 추가 (검색 결과 경로의 Populator도 확인) |
| 장바구니 항목별 선물 포장 여부·배송 예정일 | `OrderEntryWsDTO`에 `giftWrap`, `estimatedDeliveryDate` 추가 |
| 주문 내역의 커스텀 상태명 | `OrderHistoryWsDTO`·`OrderWsDTO`에 `customStatusLabel` 추가 |
| 마이페이지 회원 등급·포인트 | `UserWsDTO`에 `memberGrade`, `points` 추가 |
| 장바구니 합계 영역의 커스텀 할인 내역 | `CartWsDTO`에 `customDiscounts` 추가 |

반대로 다음은 이 패턴이 맞지 않는다.

- 다른 리소스 목록을 끼워 넣는 경우 (예: 상품 응답에 "함께 산 상품" 20개). → 별도 API(0-3) 또는 표준 레퍼런스 API.
- 특정 화면의 집계 결과 (예: 마이페이지 대시보드 숫자 모음). → 별도 API(0-3) 또는 프런트엔드 조합(0-5).
- 무거운 외부 시스템 조회 (예: 실시간 재고 조회 ERP 호출). → 별도 API로 분리해 필요할 때만 호출.
- 입력에 따라 결과가 달라지는 계산 (예: 우편번호별 배송비 시뮬레이션). → 입력을 받는 별도 API.

#### 0-2-1-2. 기존 API를 그대로 쓰던 기능에 영향이 있는가?

**결론: 필드 추가만으로는 기존 기능이 깨지는 일은 드물다. 다만 "영향 없음"은 아니고, 아래 항목을 확인해야 한다.**

##### 프런트엔드 (Composable Storefront)

| 항목 | 영향 |
|---|---|
| 응답 파싱 | JSON에 모르는 필드가 있어도 무시한다. 기존 화면은 깨지지 않는다 |
| 새 필드 표시 | **자동으로 보이지 않는다.** Spartacus는 엔드포인트별 `fields`를 명시 지정하는 경우가 많다. 그 문자열에 새 필드를 추가해야 응답에 들어온다 |
| `DEFAULT`/`FULL`을 쓰는 엔드포인트 | Spartacus 기본 설정에서 `fields=DEFAULT,...`처럼 레벨을 쓰는 엔드포인트(장바구니 등)는 레벨에 넣은 필드가 **바로 응답에 들어온다** **[버전 확인]** |
| 타입 | TypeScript 모델(`Product`, `Cart` 등)을 모듈 확장(module augmentation)으로 늘려야 타입 안전하게 쓸 수 있다 |
| Normalizer | 표준 Converter가 필드를 그대로 넘기면 불필요. 값 변환이 필요하면 커스텀 Normalizer 추가 |
| 상태 캐시 | 상품은 scope(list, details 등) 단위로 캐시된다. 어느 scope의 fields에 넣을지에 따라 값이 보이는 화면이 달라진다 |
| SSR·전송량 | DEFAULT에 무거운 필드를 넣으면 모든 페이지의 응답이 커진다 |

##### 백엔드와 다른 호출자

| 항목 | 영향 |
|---|---|
| **Populator 실행 비용** | `fields`는 **응답에서 어떤 필드를 보낼지만** 정한다. Populator는 fields와 관계없이 실행되는 것이 일반적이다. 따라서 추가 Populator의 비용은 **그 Converter를 쓰는 모든 경로**(다른 OCC API, Accelerator가 남아 있다면 그쪽, 배치·메일 등)에 붙는다. 상품은 ProductOption으로 Populator를 제한하는 구조가 있으니, 무거운 값은 옵션으로 분리를 검토한다 **[버전 확인]** |
| 다른 API 소비자 | 모바일 앱, 외부 연동 등이 같은 API를 쓰면 응답이 커진다. 엄격한 스키마 검증을 하는 소비자는 드물지만 확인한다 |
| 이름 충돌 | SAP가 이후 버전에서 같은 이름의 필드를 추가하면 충돌한다. 커스텀 필드는 접두사(예: `custom`, 프로젝트 약어)를 붙이는 것을 권한다 |
| 캐시 | CDN·HTTP 캐시가 있으면 응답 변경 후 무효화가 필요하다 |
| 권한 | 추가 필드가 민감 정보라면, 그 API의 기존 권한(익명 허용 등)으로 노출되어도 되는지 확인한다 |
| API 문서 | Swagger(OpenAPI) 문서에 반영되는지, 소비자에게 알릴지 결정한다 |

### 0-2-2. 0-2-1 말고 다른 수정 패턴

| 패턴 | 언제 | 구현 방법 | 기존 호출자에 대한 영향 |
|---|---|---|---|
| D. 하위 업무 로직 확장 | 계약(URL·요청·응답)은 같고 처리 규칙만 다르다 (검증, 계산, 후처리) | Service·Strategy 빈 교체(alias), Hook 구현 추가 (예: 장바구니 담기·주문 확정 Hook), Facade 확장 **[버전 확인: 대상 API의 실제 확장 지점]** | **큼.** 같은 Service를 쓰는 모든 채널에 적용된다. OCC 전용으로 하려면 OCC용 빈을 분리해야 한다 |
| D'. 검증기(Validator) 교체·추가 | 입력 검증 규칙만 다르다 | OCC 컨트롤러가 주입받는 Validator 빈을 alias로 교체하거나 체인에 추가 | 그 API의 모든 호출자. 기존 요청이 거부될 수 있으므로 주의 |
| E. 요청 계약 확장 | 선택 파라미터나 요청 본문 필드가 필요하다 | 요청 WsDTO에 속성 추가 → Data로 매핑 → 처리. 쿼리 파라미터는 컨트롤러 재정의(F)가 필요 | **필수로 만들면 기존 호출자가 깨진다.** 반드시 선택 항목으로, 없을 때는 기존 동작 |
| F. 같은 URL 재정의 | 컨트롤러 수준의 처리 변경이 필요하다 (파라미터 해석, 다른 Facade 호출, 응답 구성) | 커스텀 OCC extension에 같은 매핑의 메서드를 만들고 `@RequestMappingOverride` 지정 | 원래 메서드는 더 이상 호출되지 않는다. 표준 업그레이드 시 원래 메서드의 변경을 직접 따라가야 한다 |
| 응답 레벨만 조정 | 필드는 이미 있으나 기본 레벨에 없다 | FieldSetLevelMapping에서 레벨에 필드 추가 | 0-2-1-2의 레벨 관련 영향과 같다 |
| HTTP 공통 처리 | 헤더, 로깅, 추적 ID, 공통 검사 | Filter·HandlerInterceptor | 모든 OCC 요청에 적용 |
| 오류 응답 변환 | 커스텀 예외를 특정 오류 코드로 보내고 싶다 | 예외 → 오류 DTO 변환 설정, `@ExceptionHandler` | 오류 응답 형식이 바뀌면 프런트엔드 오류 처리에 영향 |

권장하는 검토 순서는 **응답 데이터(C) → 하위 업무 로직(D) → 요청 계약(E) → 같은 URL 재정의(F)**이다. 뒤로 갈수록 표준과의 결합이 강해지고 업그레이드 비용이 커진다.

---

## 0-3. 완전히 새로운 OCC 엔드포인트를 작성하기

### 구성 요소

```text
[백엔드: 커스텀 OCC extension]
  Controller (BaseController 상속, @Controller, @RequestMapping("/{baseSiteId}/..."))
    - 보안: @Secured / 접근 권한 설정, 익명 허용 여부
    - 문서: @ApiBaseSiteIdParam, @ApiFieldsParam, Swagger 설명
    - fields 파라미터 지원: DataMapper.map(data, XxxWsDTO.class, fields)
  WsDTO + FieldSetLevelMapping (BASIC/DEFAULT/FULL 정의)
  Validator (요청 본문 검증)
  Facade (재사용 또는 신규) → Service → Model
  예외 처리와 오류 응답

[프런트엔드: Composable Storefront]
  endpoint 설정 (backend.occ.endpoints.xxx)
  Adapter (OCC 호출) → Connector → Facade/Service (상태 관리)
  Normalizer/Serializer (필요하면)
  컴포넌트 (CMS 컴포넌트 매핑 또는 라우트)
```

### URL 설계 기준

- **기존 리소스의 하위 리소스로 둔다**: `/{baseSiteId}/users/{userId}/carts/{cartId}/giftwrap` 처럼 기존 경로 아래에 둔다. `userId`·`cartId`의 권한 검사(본인 장바구니인지 등)를 표준과 같은 방식으로 적용할 수 있다.
- 기존 표준 경로와 겹치지 않게 한다. 겹치면 F(재정의)와 구분이 모호해진다.
- 표준과 같은 규칙(`current`·`anonymous` 사용자 ID, `fields`, 페이징 파라미터 `currentPage`·`pageSize`·`sort`)을 따른다. Spartacus 쪽 공통 처리를 재사용하기 쉽다.

### 장단점

| 장점 | 단점 |
|---|---|
| 기존 API·호출자에 영향이 없다 | 백엔드와 프런트엔드 양쪽에 새 코드가 생긴다 |
| 계약을 화면·업무에 맞춰 자유롭게 설계 | 보안·캐시·오류 처리·문서를 직접 챙겨야 한다 |
| 표준 업그레이드와 충돌이 적다 | 표준 API와 비슷한 API가 생기면 이중 관리가 된다 |

---

## 0-4. 0-2(기존 확장)와 0-3(신규 작성)의 선택 기준

### 판단 흐름

```text
Q1. 같은 리소스·같은 업무 동작인가?           ── 아니오 → 0-3 신규
     │ 예
Q2. 차이가 "응답 데이터 부족"뿐인가?         ── 예 → 0-2-1 응답 확장 (C)
     │ 아니오
Q3. 차이가 "처리 규칙"이고 모든 채널에 적용해도 되는가?
                                            ── 예 → 하위 로직 확장 (D)
     │ 아니오 (OCC/특정 화면에만 필요)
Q4. 기존 계약을 유지한 채 선택 파라미터로 표현 가능한가?
                                            ── 예 → 요청 계약 확장 (E) 또는 재정의 (F)
     │ 아니오
     └→ 0-3 신규
```

### 판단 요소별 비교

| 판단 요소 | 0-2(기존 확장)이 유리 | 0-3(신규)이 유리 |
|---|---|---|
| 리소스 | 기존 리소스의 속성·동작 | 새 리소스, 또는 여러 리소스의 조합 |
| 계약 | 기존 계약에 **추가만** 하면 된다 | 기존 계약을 바꾸거나 빼야 한다 |
| 적용 범위 | 모든 호출자에게 적용되어도 된다 | 특정 화면·채널에만 필요하다 |
| 비용 | 추가 비용이 작다 | 무거워서 필요할 때만 호출해야 한다 |
| 권한 | 기존 API의 권한과 같다 | 다른 권한이 필요하다 (예: 로그인 필수) |
| Spartacus | 표준 컴포넌트를 그대로 쓰고 일부만 확장 | 표준 컴포넌트로는 표현할 수 없는 화면 |
| 업그레이드 | 확장 지점(Populator, Hook, Strategy)이 SAP가 공개한 것 | 표준 컨트롤러 재정의가 필요해 표준 추적 비용이 크다 |
| 소비자 | 같은 API의 다른 소비자가 없거나 영향이 작다 | 외부 소비자가 있어 기존 API 변경이 어렵다 |

### 실무상 기준 한 줄

- **"기존 API에 붙일 수 있는가"가 아니라 "기존 API의 의미가 바뀌는가"로 판단한다.** 의미가 그대로이고 정보만 늘면 0-2, 의미가 바뀌거나 새 의미가 필요하면 0-3.
- F(같은 URL 재정의)는 0-2와 0-3의 중간이다. 계약은 같고 컨트롤러 구현은 새로 쓴다. **표준 메서드를 통째로 복사하게 된다면** 0-3(새 URL)이나 D(하위 확장)를 먼저 다시 검토한다.

---

## 0-5. 그 밖의 이행 방법

### 5-1. 표준 OCC API 여러 개를 조합하기 (패턴 B)

가능하다. 조합하는 위치에 따라 두 가지가 있다.

| 방식 | 내용 | 적합 | 부적합 |
|---|---|---|---|
| **프런트엔드 조합** | Spartacus에서 표준 API를 여러 번 호출해 한 화면을 구성. Spartacus 표준 화면도 이 방식이다 (예: 상품 상세 = 상품 + 리뷰 + 레퍼런스 + CMS) | **조회**, 각 호출이 독립적, 일부 실패해도 화면 표시 가능 | **원자성이 필요한 갱신** (둘 다 성공하거나 둘 다 실패해야 함), 호출 횟수가 많아 성능 문제 |
| **백엔드 조합 (신규 엔드포인트)** | 신규 OCC 엔드포인트 안에서 표준 Facade 여러 개를 호출 | 트랜잭션·순서 보장, 호출 횟수 감소 | 이것은 결국 0-3이다. 표준 Facade 재사용만으로 끝나면 비용이 작다 |

갱신 조합의 예:

- "장바구니에 담고 바로 배송 방법을 설정" → 프런트엔드 순차 호출로도 가능. 중간 실패 시 되돌릴 필요가 없으면 B로 충분.
- "포인트 사용과 주문 확정을 함께" → 한쪽만 성공하면 안 되므로 백엔드에서 처리(0-3 또는 D, 주문 확정 Hook).

### 5-2. CMS로 옮기기 (패턴 H)

Accelerator의 페이지 컨트롤러가 Model에 넣던 값 중 **콘텐츠 성격**인 것(배너, 안내 문구, 링크 목록, 공지)은 OCC API를 만들 필요가 없다.

- Composable Storefront는 CMS 페이지 API로 페이지 구성과 컴포넌트 속성을 받는다.
- 커스텀 CMS 컴포넌트 타입을 만들면 그 속성이 CMS API 응답에 실린다. 새 REST 엔드포인트 없이 데이터를 전달하는 방법이다. **[버전 확인: 커스텀 컴포넌트 속성이 응답에 실리는 조건]**
- 프런트엔드는 컴포넌트 타입을 Angular 컴포넌트에 매핑만 한다.

### 5-3. 프런트엔드만으로 처리 (패턴 H)

- 표시 형식, 정렬, 필터링, 클라이언트 검증, 화면 이동 등 서버 데이터가 필요 없는 처리.
- 사이트 설정 값(화면 동작 플래그 등)은 Spartacus 설정으로 옮길 수 있다.
- 단, **보안·가격·재고 판정은 프런트엔드로 옮기면 안 된다.** 서버에서 다시 검증해야 한다.

### 5-4. SAP 제공 OCC extension·기능 모듈 활용 (패턴 A의 확장)

- 표준 Commerce OCC에 없는 기능도 SAP 제공 extension(B2B, 위시리스트 성격 기능, 주문 승인, ASM, 개인화 등)과 Spartacus 기능 라이브러리가 짝으로 제공되는 경우가 있다. 커스텀 개발 전에 이 목록을 확인한다.

### 5-5. 검색 결과 확장은 Solr 쪽에서

- 상품 목록·검색 결과에 값을 추가하는 경우, 상품 상세와 다른 경로(검색 결과 Converter)를 탄다. **색인 속성(indexed property) 추가 + 검색 결과 Populator**가 필요할 수 있다. 상품 상세 Populator만 추가하면 목록에는 나오지 않는다.

### 5-6. Commerce 밖의 조합 계층 (BFF 등)

- 여러 시스템(Commerce + 외부 재고 + 리뷰 서비스)을 합쳐야 하면, Commerce에 OCC를 추가하지 않고 별도 BFF·API 게이트웨이에서 조합하는 구성도 있다.
- 장점: Commerce 업그레이드와 분리된다. 단점: 인증 토큰 전달, 운영 대상 증가. 프로젝트 아키텍처 결정 사항이다.

### 5-7. 이행하지 않기 (폐기)

- Accelerator 전용 동작(JSP 조각 반환, 세션 기반 임시 처리, 더 이상 쓰지 않는 화면)은 이행 대상에서 뺀다. 조사 단계에서 "이행 불필요"를 명시적으로 판정하는 것도 결과물이다.

---

## 정리: 기능 하나를 판정하는 순서

```text
1. 업무 기능으로 분해 (페이지 컨트롤러는 1:N)
2. 각 기능의 필요한 값마다:
   - 콘텐츠인가?                        → CMS (5-2)
   - 서버 데이터가 필요 없는가?         → 프런트엔드 (5-3) / 폐기 (5-7)
   - 표준 OCC (SAP extension 포함)에 있는가? → 대체 (0-1)
   - 표준 여러 개를 합치면 되는가?       → 조합 (5-1)
3. 남은 차이마다:
   - 기존 리소스의 속성 추가             → 응답 확장 (0-2-1)
   - 처리 규칙만 다름                    → 하위 로직 확장 (D)
   - 컨트롤러 수준의 차이                → 요청 확장 (E) / 재정의 (F)
   - 새 의미·새 리소스                   → 신규 엔드포인트 (0-3)
4. 0-2를 고른 기능은 0-2-1-2의 영향 항목을 점검
```

### 이 저장소의 도구와의 연결

| 단계 | 쓸 수 있는 산출물 |
|---|---|
| 1. 업무 기능 분해 | staticAnalyzer `controller-endpoints.csv`(accelerator), 호출 그래프 |
| 2. 화면에 필요한 값 | controller-model-analyzer (Model 속성 목록) |
| 2. 표준 OCC에 있는가 | occ-response-analyzer (`occ-response-items.csv`, `occ-fieldset-mappings.csv`) |
| 2. 대응 판정 방법 | `accelerator-model-vs-occ-response-comparison.md` |
| 3. 확장 지점의 실체 | spring-bean-analyzer-from-xml (Converter·Populator·Strategy의 최종 빈) |

---

## 추가 논의가 필요해 보이는 부분

1. **같은 Service를 쓰는 다른 채널의 범위**: Accelerator를 이행 기간 동안 병행 운영하는가? 병행하면 D(하위 로직 확장)와 Populator 추가가 Accelerator에도 적용된다.
2. **OCC API의 다른 소비자 유무**: 모바일 앱, 외부 연동이 있으면 0-2의 기준이 엄격해진다.
3. **F(`@RequestMappingOverride`)를 어디까지 허용할지**: 업그레이드 정책과 함께 프로젝트 규칙으로 정할 필요가 있다.
4. **커스텀 필드 명명 규칙**: 접두사 사용 여부.
5. **[버전 확인] 항목의 실제 확인**: FieldSetLevelMapping 병합, Spartacus 엔드포인트별 기본 fields, ProductOption과 Populator의 관계, 대상 API의 Hook 유무.

## 참고 (SAP 공식 문서, 대상 버전에 맞춰 확인)

- Extending the REST API: 커스텀 OCC 컨트롤러와 `@RequestMappingOverride`
- Fields Configuration Format: BASIC·DEFAULT·FULL과 FieldSetLevelMapping
- Data Mapping Mechanism: Data와 WsDTO 매핑
- Converters and Populators: Populator 확장
- Composable Storefront 문서: Extending Built-in Models, Connecting to Other Systems(Adapter·Connector), Configuring Endpoints, CMS Components

링크는 `sap-commerce-composable-storefront-migration-discussion.md` 7장에 있다.
