# [S0] Accelerator 엔드포인트를 OCC 엔드포인트로 옮길 때의 이행 패턴 검토

[S0-L1] 작성일: 2026-11-27 (논의 전제 반영, 웹 검색 검증 반영, Accelerator extension 최종 비활성화 전제 반영)

[S0-L2] 질문 원문은 `Question.txt`에 있다. 질문 번호(0~0-5)에 맞춰 답한다. 공개 자료로 확인한 내용과 확인하지 못한 내용은 맨 끝의 "웹 검색 검증 결과"에 정리했다. 공개 자료로 확인하지 못한 부분은 본문에 **[미확인]**으로 표시했다. 2211-jdk21의 실제 소스로 확인해야 한다.

[S0-L3] 번호 규칙: 모든 요소에 겹치지 않는 번호를 붙였다. 타이틀은 `[S절번호]`(예: 제목 `S0`, 장 `S4`, 그 아래 절 `S4.1`), 내용행은 `[S절번호-L순번]`(목록 항목과 코드 블록도 내용행 하나로 센다), 표는 `[S절번호-T순번]`이다. 본문 안의 `0-2-1`, `V1`, `U1` 등은 원래의 질문 번호와 검증 결과 ID이다.

---

## [S1] 전제와 그 전제가 답에 주는 영향

### [S1.1] 논의의 전제 (요약)

- [S1.1-L1] 현행 Accelerator와 같은 소스·DB로 시작하고, zip에 포함된 OCC extension을 활성화해 OCC 구성(essential/project data, OAuth 클라이언트)을 더한다.
- [S1.1-L2] Accelerator 관련 파일은 삭제하지 않고 충돌할 때만 조정한다.
- [S1.1-L3] 이행 전부터 쓰던 Accelerator 관련 extension은 **최종적으로 비활성화한다**(서비스하지 않음).
- [S1.1-L4] 작업의 약 90%는 Controller 계층이다. facade·service·dao·model은 재사용하고, 정말 필요할 때만 추가한다(model은 확실한 근거가 있을 때만).

### [S1.2] 전제에서 바로 나오는 결론 7가지

1. [S1.2-L1] **이행의 중심은 "Accelerator 컨트롤러가 하던 일을 OCC 컨트롤러로 옮기는 일"이다.** 메서드의 처리를 facade 호출(재사용), 컨트롤러 안의 로직(OCC 컨트롤러로 이전, 정말 필요하면 facade로), 화면 처리(프런트엔드·CMS)로 나눈다.
2. [S1.2-L2] **Data 객체는 이미 있다.** Accelerator 화면의 커스텀 값은 대부분 `*Data`와 Populator로 채워져 있다. 그래서 0-2-1은 많은 경우 **WsDTO 필드와 fields 매핑 추가로 끝난다.**
3. [S1.2-L3] **Accelerator 화면은 최종적으로 없어진다. 공유 계층 변경의 Accelerator 영향은 병행 기간에만 생긴다.** facade·service·Populator를 바꾸면, 비활성화 전까지는 Accelerator 화면에도 함께 적용된다. 비활성화 후에는 OCC와 같은 계층을 쓰는 다른 채널(배치, 메일, 백오피스, 외부 연동)만 영향을 받는다. 그래서 업무 규칙의 차이는 공유 계층에서 고치는 선택지(패턴 D)를 꺼릴 이유가 줄어든다.
4. [S1.2-L4] **재사용할 수 있는 것은 "코어 쪽 빈"뿐이고, 스토어프런트 쪽은 비활성화와 함께 없어진다.** 스토어프런트 웹 애플리케이션 컨텍스트의 빈은 OCC 웹 컨텍스트에서 보이지 않는다. 비활성화 뒤에는 클래스 자체도 로드되지 않는다. OCC에 필요한 것은 비활성화 전에 OCC 쪽으로 옮겨야 한다(아래 표).
5. [S1.2-L5] **Facade가 세션을 쓰는 것은 대체로 괜찮다.** OCC 필터 체인(`BaseSiteMatchingFilter`, `UserMatchingFilter`, `CartMatchingFilter` 등)이 요청마다 사이트·사용자·장바구니(`cartId`)를 세션에 설정하므로 `getSessionCart()` 같은 호출도 동작한다(검증 결과 V5). 다만 **Accelerator 컨트롤러나 필터가 HTTP 세션에 직접 넣던 커스텀 속성**은 OCC에 없다.
6. [S1.2-L6] **DB를 그대로 가져오므로 CMS 데이터도 그대로 있다.** Composable Storefront는 이를 CMS API로 읽으므로, 화면 구성 일부는 새 OCC 엔드포인트 없이 이행할 수 있다(0-5).
7. [S1.2-L7] **비활성화 범위를 extension 의존관계로 정해야 한다.** 비활성화하는 extension에 OCC가 쓰는 빈이 있으면 OCC도 함께 동작하지 않는다. 예를 들면 커스텀 core·facades extension, addon의 코어 쪽 빈이 그렇다. 그래서 "스토어프런트(웹)만 끄는가, addon·accelerator 모듈까지 끄는가"를 정하고, OCC 쪽 extension의 의존관계(`requires-extension`)를 extension-analyzer로 확인한다.

### [S1.3] 재사용 가능 여부: 정의 위치로 판정

[S1.3-T1] (표)

| 정의 위치 | OCC에서 재사용 | 비고 |
|---|---|---|
| core·facades extension의 `*-spring.xml` (글로벌·코어 컨텍스트) | **가능** | facade, service, dao, converter, populator, strategy |
| `*-beans.xml`의 Data 클래스 | **가능** | WsDTO는 별도로 필요 |
| items.xml의 model | **가능** | 추가하지 않는 전제와 맞다 |
| 스토어프런트 `*-web-spring.xml`, `spring-mvc-config.xml` 등 웹 컨텍스트 | **불가** | 폼 Validator, 컨트롤러 헬퍼, 스토어프런트 전용 strategy, 인터셉터. OCC에 필요하면 OCC extension에 다시 정의 |
| 스토어프런트 `src`의 클래스 (Form, Validator, util) | **불가**(클래스 경로가 다르고, 비활성화 후에는 없음) | OCC extension에 다시 만든다. 병행 기간에 양쪽이 같은 로직을 써야 하면 공용 core extension으로 옮긴다 |
| addon의 웹 쪽 빈 (`*addon/resources/.../web/spring`) | **불가** | OCC에 필요하면 OCC extension에 다시 정의 |
| addon의 코어 쪽 빈 | 가능. 단 **addon을 비활성화하면 함께 없어진다** | OCC가 쓰면 그 addon을 비활성화 대상에서 빼거나 빈을 옮긴다 |

[S1.3-L1] spring-bean-analyzer-from-xml의 결과(빈이 어느 컨텍스트에 등록되는지)로 판정할 수 있다.

[S1.3-L2] 2211의 커스텀 OCC extension은 자체 웹 애플리케이션이 아니다. `commercewebservices`의 웹 컨텍스트에 붙는다.
- [S1.3-L3] `<ext>.controllers` 패키지의 컨트롤러가 그 컨텍스트에 자동으로 추가된다.
- [S1.3-L4] `resources/occ/v2/<ext>/web/spring/<ext>-web-spring.xml`이 그 컨텍스트에 import된다(V2).
- [S1.3-L5] 따라서 OCC용 빈(WsDTO 매핑, FieldSetLevelMapping, Validator)은 이 파일에 정의한다.

---

## [S2] 0. 이행 작업에서 나타나는 패턴의 전체 그림

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

| 패턴 | 요약 | 이 전제에서의 주 작업 | 병행 기간 중 Accelerator 영향 |
|---|---|---|---|
| A. 표준 API로 대체 (0-1) | 표준 OCC + Spartacus 표준 기능 | 없음(필요한 OCC extension 활성화) | 없음 |
| B. 표준 API 조합 (0-5) | 표준 API 여러 개를 프런트엔드에서 조합 | 프런트엔드만 | 없음 |
| C. 응답 데이터 확장 (0-2-1) | 같은 URL의 응답에 필드 추가 | WsDTO + fields 매핑. Data·Populator는 대개 이미 있음 | Populator를 새로 넣을 때만 있음 |
| D. 하위 업무 로직 확장 (0-2-2) | 같은 계약, 처리 규칙만 변경 | Service·Strategy·Hook | 있음(공유 계층). 비활성화 후에는 없음 |
| E. 요청 계약 확장 (0-2-2) | 선택 파라미터, 요청 DTO 필드 추가 | WsDTO, 컨트롤러 재정의 | 없음 |
| F. 같은 URL 재정의 (0-2-2) | `@RequestMappingOverride`로 처리 교체 | 커스텀 OCC 컨트롤러 | 없음 |
| G. 신규 엔드포인트 (0-3) | 새 URL, 새 계약 | 커스텀 OCC 컨트롤러 + 기존 facade 호출 | 없음 |
| H. API가 필요 없음 (0-5) | CMS, 프런트엔드, 설정 | CMS 컴포넌트 매핑 | 없음 |

[S2.2-L2] 이 전제에서는 **G가 가장 많이 나올 가능성이 높다.** facade를 재사용하므로, 표준 OCC에 없는 기능은 대부분 "기존 facade를 부르는 새 OCC 컨트롤러"가 된다. C와 F가 그 다음이다. D는 업무 규칙 자체가 바뀌는 경우에 쓴다. 최종적으로 화면 채널이 OCC 하나가 되므로, Accelerator 영향 때문에 D를 피할 필요는 없다(병행 기간의 회귀 확인만 필요).

---

## [S3] 0-1. 기존 OCC API로 완전히 대체할 수 있는 경우

### [S3.1] 대체 가능 판정 조건 (모두 만족)

1. [S3.1-L1] **입력**: Accelerator가 받던 파라미터를 OCC API의 경로·쿼리·본문으로 표현할 수 있다.
2. [S3.1-L2] **처리**: 호출되는 facade/service가 같거나 업무상 같은 결과를 낸다. 부수 효과(이벤트, 메일, 감사 로그)도 포함한다.
   - [S3.1-L3] 이 전제에서는 **같은 facade를 부르는지**를 호출 그래프로 비교하면 판정이 빠르다.
3. [S3.1-L4] **출력**: 화면에 필요한 값이 OCC 응답(fields 지정 포함)에 모두 있다.
4. [S3.1-L5] **조건**: 권한, 로그인 여부, 사이트·통화·언어 컨텍스트가 같은 방식으로 적용된다.

### [S3.2] 주의할 차이

- [S3.2-L1] **컨트롤러 안의 로직:** facade 호출 전후의 처리(값 보정, 조건 분기, 추가 facade 호출)가 있으면 "같은 facade를 부른다"만으로는 대체가 아니다.
- [S3.2-L2] **세션 의존:** Accelerator 컨트롤러가 HTTP 세션에 넣던 커스텀 값은 OCC에 없다.
- [S3.2-L3] **fields 차이:** "값이 없다"고 판정하기 전에 `FULL`이나 명시 지정으로 다시 확인한다(occ-response-analyzer의 `occ-fieldset-mappings.csv`).
- [S3.2-L4] **OCC extension 미활성:** B2B 등은 별도 OCC extension(예: `b2bocc`)을 켜야 API가 생긴다.

### [S3.3] 이행 작업

[S3.3-L1] 백엔드는 없음 또는 OCC extension 활성화, 프런트엔드는 Spartacus 표준 기능 + 필요하면 endpoint `fields` 조정. Accelerator 컨트롤러는 파일을 삭제하지 않지만, extension 비활성화와 함께 서비스가 끝난다.

---

## [S4] 0-2. 기존 OCC API를 일부 수정하는 경우

### [S4.1] 0-2-1. 같은 엔드포인트에 응답 항목을 추가하기

#### [S4.1.1] 구현 순서 (이 전제에서의 형태)

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
- [S4.1.1-L5] 1·2가 **스토어프런트나 addon extension에 정의되어 있으면** 비활성화와 함께 없어진다(스토어프런트의 beans.xml·spring.xml 등). 이 경우 core·facades extension으로 옮긴다.
- [S4.1.1-L6] 4를 하지 않아도 `fields=...,originCountry`처럼 명시 지정하면 응답에 나온다. 레벨에 넣는 것은 "레벨로만 fields를 지정하는 호출자에게도 기본으로 보일지"를 정하는 일이다.
- [S4.1.1-L7] FieldSetLevelMapping 빈(`parent="fieldSetLevelMapping"`, `dtoClass`, `levelMapping`)은 **OCC 웹 컨텍스트에 정의해야 한다**. `DefaultFieldSetBuilder`가 같은 컨텍스트에서 읽기 때문이다(V3).
- [S4.1.1-L8] 같은 `dtoClass`에 빈을 하나 더 정의하면 기존 정의와 합쳐지는지 덮어쓰는지는 **[미확인]**이다. `DefaultFieldSetLevelHelper.addToLevelMap`의 동작을 소스로 확인한다. occ-response-analyzer는 이 경우를 `MULTIPLE_MAPPINGS`로 보고한다.

#### [S4.1.2] 0-2-1-1. 어떤 경우에 이 패턴을 검토할 수 있는가?

[S4.1.2-L1] 이 전제에서는 판단이 쉬워진다. 판단 기준은 두 가지다.

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
  - [S4.1.2-L8] 그래서 별도 API(0-3) 또는 표준 API 조합(0-5)이 된다.
  - [S4.1.2-L9] controller-model-analyzer의 결과에서 "Data 속성 경로"인지 "별도 Model 속성"인지로 구분할 수 있다.
- [S4.1.2-L10] 다른 리소스 목록, 특정 화면 집계, 무거운 외부 조회, 입력에 따라 달라지는 계산 → 별도 API.

#### [S4.1.3] 0-2-1-2. 기존 API를 그대로 쓰던 기능에 영향이 있는가?

[S4.1.3-L1] **결론**
- [S4.1.3-L2] WsDTO와 fields만 추가하는 경우(Data·Populator가 이미 있는 경우)에는 기존 OCC 호출자에 대한 영향이 작다.
- [S4.1.3-L3] Populator를 새로 추가하면, 같은 Converter를 쓰는 다른 경로에 영향이 있다. 병행 기간에는 Accelerator 화면도 포함된다.
- [S4.1.3-L4] 주로 확인할 곳은 **같은 API를 쓰는 Spartacus의 다른 화면**이다(아래 표).

##### [S4.1.3.1] 프런트엔드 (Composable Storefront)

[S4.1.3.1-T1] (표)

| 항목 | 영향 |
|---|---|
| 응답 파싱 | 모르는 필드는 무시한다. 기존 화면은 깨지지 않는다 |
| 새 필드 표시 | 엔드포인트의 `fields`가 명시 목록이면 **자동으로 보이지 않는다**. 레벨(DEFAULT/FULL)을 쓰면 그 레벨에 넣은 필드가 **바로 들어온다**. 아래 표 참고 |
| 타입 | TypeScript 모델(`Product`, `Cart` 등)을 모듈 확장(module augmentation)으로 늘린다 |
| Normalizer | 값 변환이 필요할 때만 커스텀 Normalizer |
| 상태 캐시 | 상품은 scope(list, details 등) 단위로 캐시된다. 어느 scope의 fields에 넣을지에 따라 값이 보이는 화면이 달라진다 |
| 전송량 | DEFAULT에 무거운 필드를 넣으면 모든 페이지의 응답이 커진다 |

[S4.1.3.1-L1] Spartacus 기본 endpoint 설정에서 확인한 내용이다(V1, Spartacus `develop` 브랜치 기준. 실제로 쓰는 버전으로 다시 확인).

[S4.1.3.1-T2] (표)

| 엔드포인트 | fields | 레벨에 추가한 필드 |
|---|---|---|
| 상품 `product.default` | `DEFAULT,averageRating,images(FULL),...` | DEFAULT에 넣으면 들어옴 |
| 상품 `list`·`details`·`attributes` 등 scope, 상품 검색 | 명시 목록 (`code,name,...`) | 들어오지 않음. scope 설정에 추가 필요 |
| 장바구니 `cart`·`carts` | `DEFAULT,...,entries(totalPrice(...),product(...),...),...` | `CartWsDTO`의 DEFAULT 필드는 들어옴. **`entries`는 명시 목록**이라 `OrderEntryWsDTO` 필드는 들어오지 않음 |
| 주문 상세 `orderDetail`, 주문 확정 `placeOrder` | `FULL` | FULL에 넣으면 들어옴 |
| 주문 내역 `orderHistory`, 사용자 `user` | 지정 없음 | 서버 기본값(DEFAULT)에 넣으면 들어옴 |

##### [S4.1.3.2] 백엔드와 다른 호출자

[S4.1.3.2-T1] (표)

| 항목 | 영향 |
|---|---|
| WsDTO·fields 추가 | OCC 웹 계층에만 적용된다 |
| **Populator 새로 추가** | `fields`는 응답에 보낼 필드만 정한다. Populator 실행 여부와는 관계가 없는 것이 일반적이다. 그래서 그 Converter를 쓰는 **모든 경로**에 비용이 붙는다. **다른 OCC API, 배치, 메일**, 병행 기간에는 **Accelerator 화면**도 해당한다 |
| 다른 API 소비자 | 모바일 앱·외부 연동이 같은 API를 쓰면 응답이 커진다 |
| 이름 충돌 | SAP가 이후 버전에서 같은 이름의 필드를 추가하면 충돌한다. 커스텀 필드 접두사 규칙을 검토한다 |
| 캐시 | CDN·HTTP 캐시가 있으면 무효화가 필요하다 |
| 권한 | 익명 허용 API에 민감한 필드를 넣지 않는다. Accelerator에서는 로그인 화면에서만 보이던 값이 OCC에서는 익명 API로 노출될 수 있다 |

### [S4.2] 0-2-2. 0-2-1 말고 다른 수정 패턴

[S4.2-T1] (표)

| 패턴 | 언제 | 구현 방법 | 영향 |
|---|---|---|---|
| D. 하위 업무 로직 확장 | 계약은 같고 처리 규칙만 다르다 | Service·Strategy 빈 교체(alias), Hook 추가, facade 확장 (대상 API에 어떤 확장 지점이 있는지는 **[미확인]**) | 같은 계층을 쓰는 모든 채널(배치, 메일, 백오피스, 병행 기간의 Accelerator)에 적용된다. 업무 규칙 자체의 변경이면 이것이 맞는 위치다. OCC 요청에만 필요한 처리면 OCC 컨트롤러 쪽(F 또는 G)에서 한다 |
| D'. Validator 교체·추가 | 입력 검증 규칙만 다르다 | OCC 컨트롤러가 주입받는 Validator 빈을 OCC 웹 컨텍스트에서 교체·추가 | OCC 요청에만 적용. Accelerator 폼 Validator는 재사용할 수 없고 비활성화로 없어지므로, 규칙을 옮겨 다시 작성 |
| E. 요청 계약 확장 | 선택 파라미터, 요청 본문 필드 추가 | 요청 WsDTO에 속성 추가 → Data로 매핑. 쿼리 파라미터는 F가 필요 | 필수로 만들면 기존 호출자가 깨진다. 반드시 선택 항목으로 |
| F. 같은 URL 재정의 | 컨트롤러 수준의 처리 변경 | 커스텀 OCC extension에 **완전히 같은** 매핑의 메서드 + `@RequestMappingOverride`(아래 주의) | 원래 메서드는 호출되지 않는다. 업그레이드 시 표준 메서드의 변경을 직접 따라가야 한다 |
| 응답 레벨만 조정 | 필드는 있으나 기본 레벨에 없다 | FieldSetLevelMapping 수정 | 0-2-1-2의 레벨 관련 영향 |
| HTTP 공통 처리 | 헤더, 로깅, 공통 검사 | OCC 웹 컨텍스트의 Filter·Interceptor | 모든 OCC 요청 |
| 오류 응답 변환 | 커스텀 예외를 특정 오류 코드로 | 예외 → 오류 DTO 변환 설정 | 프런트엔드 오류 처리에 영향 |

[S4.2-L1] **F(`@RequestMappingOverride`)의 주의점** (V4, 공개 예제 기준)
- [S4.2-L2] 어노테이션: `de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride`.
- [S4.2-L3] 매핑이 **완전히 같아야** 한다. 경로 변수 이름만 달라도(`{productCode}`와 `{productId}`) 다른 매핑으로 취급된다. 이때는 기동 시가 아니라 **요청 시** "Ambiguous handler methods" 오류가 난다. 원래 `PUT, POST` 매핑의 `PUT`만 재정의하는 것도 안 된다.
- [S4.2-L4] 우선순위는 `priorityProperty`로 지정한 프로퍼티나 `requestMappingOverride.<클래스 FQN>.<메서드명>.priority`로 정한다. 값이 없으면 0이고, **가장 높은 값**이 쓰인다.
- [S4.2-L5] ASM 모드 등 특정 경로에서 재정의가 호출되지 않았다는 보고도 있다. 재정의한 API는 사용 경로별로 테스트한다.

[S4.2-L6] **이 전제에서 권하는 검토 순서**
- [S4.2-L7] 먼저 **C(응답 데이터)**로 되는지 본다.
- [S4.2-L8] 남은 차이는 성격으로 나눈다.
  - [S4.2-L9] **업무 규칙 자체**(검증·계산·후처리)의 차이 → **D(공유 계층)**. Accelerator가 최종적으로 비활성화되므로, 규칙은 facade·service에 하나만 두는 쪽이 이중 관리를 피한다.
  - [S4.2-L10] **OCC 요청 처리 방식**(파라미터 해석, 응답 구성)의 차이 → **F·G·E(OCC 웹 계층)**.
- [S4.2-L11] D를 쓰는 경우에는 병행 기간의 Accelerator 회귀와 다른 채널(배치, 메일, 백오피스) 영향을 확인한다.

---

## [S5] 0-3. 완전히 새로운 OCC 엔드포인트를 작성하기

### [S5.1] 구성 요소

[S5.1-L1] (코드 블록)
```text
[백엔드: 커스텀 OCC extension]
  ant extgen의 yocc 템플릿으로 생성. 이름은 occ로 끝나야 한다(예: xyzocc) (V2)
  2211부터 OCC는 addon이 아니라 extension 방식이다
  Controller (BaseController 상속, <ext>.controllers 패키지, @RequestMapping("/{baseSiteId}/..."))
    - 기존 facade를 주입받아 호출  ← 이 전제의 핵심. 새 facade는 정말 필요할 때만
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

[S5.2-L1] 이 전제의 작업 90%가 여기에 해당한다.

[S5.2-T1] (표)

| Accelerator 컨트롤러의 요소 | OCC 쪽 변환 |
|---|---|
| `@RequestMapping` URL (`/cart/add`, `/my-account/...`) | REST 리소스 경로로 다시 설계 (`/{baseSiteId}/users/{userId}/carts/{cartId}/...`) |
| Form 클래스 + `@Valid` + 폼 Validator | 요청 WsDTO + OCC Validator. Validator는 스토어프런트 웹 컨텍스트에 있고 비활성화로 없어지므로 OCC extension에 다시 작성 |
| 스토어프런트 전용 헬퍼·strategy·util | 비활성화로 없어진다. OCC에 필요한 것은 OCC extension(또는 공용 core extension)으로 옮긴다 |
| `model.addAttribute(...)` | 응답 WsDTO의 필드. 여러 개라면 응답 DTO 하나로 묶거나 API를 나눈다 |
| 반환 뷰 이름, `storeCmsPageInModel` | 없음. 화면 구성은 CMS API + 프런트엔드 |
| `redirect:`, `GlobalMessages` | HTTP 상태 코드 + 오류 DTO. 메시지 표시는 프런트엔드 |
| `HttpSession` 속성 사용 | 요청 파라미터, 리소스 상태(장바구니 속성 등), 프런트엔드 상태로 대체 |
| 로그인 확인 (`@RequireHardLogIn` 등) | OAuth 토큰 + `@Secured`·접근 설정 |
| CSRF 토큰 | 불필요(토큰 인증). 대신 OAuth 클라이언트·CORS 설정 |
| 컨트롤러 안의 분기·보정 로직 | OCC 컨트롤러로 옮긴다. 여러 컨트롤러에서 반복되면 facade 추가를 검토 |
| 세션 장바구니 (`cartFacade.getSessionCart()`) | 그대로 사용 가능. OCC의 `CartMatchingFilter`가 경로의 `cartId`로 세션 장바구니를 설정한다(V5) |
| 커스텀 MVC 인터셉터 | 스토어프런트의 인터셉터는 옮겨지지 않는다. 2211에서는 `commercewebservices` 웹 컨텍스트를 쓰므로, 인터셉터는 OCC extension의 web-spring.xml에서 다시 등록한다(V2) |

### [S5.3] URL 설계 기준과 장단점

- [S5.3-L1] **기존 리소스의 하위 리소스로 둔다**(예: `/{baseSiteId}/users/{userId}/carts/{cartId}/giftwrap`). 표준 경로와 겹치지 않게 하고, 표준 규칙(`current`·`anonymous`, `fields`, `currentPage`·`pageSize`·`sort`)을 따른다.
- [S5.3-L2] 장점: 기존 OCC API에 영향이 없다. 기존 facade를 부르면 구현량이 작다. 표준 업그레이드와 충돌이 적다.
- [S5.3-L3] 단점: 백엔드·프런트엔드 양쪽에 새 코드가 생긴다. 보안·캐시·오류 처리·문서를 직접 챙겨야 한다. 표준과 비슷한 API가 생기면 이중 관리가 된다.

---

## [S6] 0-4. 0-2(기존 확장)와 0-3(신규 작성)의 선택 기준

### [S6.1] 판단 흐름

[S6.1-L1] (코드 블록)
```text
Q1. 같은 리소스·같은 업무 동작인가?                ── 아니오 → 0-3 신규
     │ 예
Q2. 차이가 "응답 데이터 부족"뿐이고, 값이 Data에 이미 있는가?
                                                 ── 예 → 0-2-1 응답 확장 (C)
     │ 아니오
Q3. 차이가 업무 규칙(검증·계산·후처리) 자체인가?
     ├ 예, 다른 채널(배치·메일·백오피스)에도 같은 규칙이 맞음 → D 하위 로직 확장
     ├ 예, OCC 요청에만 맞음                       → F 재정의 또는 facade·service 추가 검토
     │ 아니오 (요청 처리 방식의 차이)
Q4. OCC 웹 계층(컨트롤러)에서 흡수할 수 있는가?
     ├ 기존 계약 그대로, 처리만 다름               → F 재정의
     ├ 선택 파라미터 추가로 표현 가능              → E 요청 확장 (+F)
     └ 계약 자체가 다름                            → 0-3 신규
```

### [S6.2] 판단 요소별 비교

[S6.2-T1] (표)

| 판단 요소 | 0-2(기존 확장)이 유리 | 0-3(신규)이 유리 |
|---|---|---|
| 리소스 | 기존 리소스의 속성·동작 | 새 리소스, 여러 리소스의 조합 |
| 계약 | 기존 계약에 **추가만** 하면 된다 | 기존 계약을 바꾸거나 빼야 한다 |
| 적용 범위 | 모든 OCC 호출자에게 적용되어도 된다 | 특정 화면에만 필요하다 |
| 비용 | 추가 비용이 작다 | 무거워서 필요할 때만 호출해야 한다 |
| 권한 | 기존 API의 권한과 같다 | 다른 권한이 필요하다 |
| 원천 | 값이 표준 API가 쓰는 Data에 이미 있다 | 컨트롤러가 다른 facade를 따로 불러 Model에 넣던 값 |
| 업그레이드 | SAP가 공개한 확장 지점(fields, WsDTO, Populator)만 쓴다 | 표준 메서드를 재정의(F)해야 한다면 차라리 새 URL이 낫다 |

### [S6.3] 실무상 기준

- [S6.3-L1] **"기존 API의 의미가 바뀌는가"로 판단한다.** 의미가 그대로이고 정보만 늘면 0-2, 의미가 바뀌거나 새 의미가 필요하면 0-3이다.
- [S6.3-L2] F는 0-2와 0-3의 중간이다. 표준 메서드를 통째로 복사하게 되거나 매핑을 완전히 맞추기 어렵다면(V4) 0-3(새 URL)을 먼저 검토한다.
- [S6.3-L3] "공유 계층을 바꾸면 Accelerator에 퍼진다"는 제약은 병행 기간에만 해당한다. 업무 규칙은 공유 계층, OCC 요청 처리 방식은 OCC 웹 계층으로 나눈다.

---

## [S7] 0-5. 그 밖의 이행 방법

### [S7.1] 5-1. 표준 OCC API 여러 개를 조합하기

[S7.1-L1] 조합은 가능하다. 어디서 조합하느냐에 따라 두 가지가 있다.

[S7.1-T1] (표)

| 방식 | 내용 | 적합 | 부적합 |
|---|---|---|---|
| **프런트엔드 조합** | Spartacus에서 표준 API를 여러 번 호출해 한 화면 구성. Spartacus 표준 화면도 이 방식이다 | **조회**, 각 호출이 독립적, 일부 실패해도 표시 가능 | **원자성이 필요한 갱신**, 호출 횟수가 많아 성능 문제 |
| **백엔드 조합** | 신규 OCC 엔드포인트에서 기존 facade 여러 개를 호출 | 순서·트랜잭션 보장, 호출 감소 | 결국 0-3이다. 다만 이 전제에서는 facade 재사용이라 비용이 작다 |

- [S7.1-L2] 예: "장바구니 담기 후 배송 방법 설정"처럼 중간 실패 시 되돌릴 필요가 없으면 프런트엔드 순차 호출로 충분하다.
- [S7.1-L3] 예: "포인트 사용과 주문 확정"처럼 한쪽만 성공하면 안 되면 백엔드에서 처리해야 한다.
- [S7.1-L4] **Accelerator 페이지 컨트롤러가 facade 여러 개를 불러 Model을 채우던 경우**가 대표적인 조합 후보다. 각 facade에 대응하는 표준 API가 있으면 프런트엔드 조합으로, 없으면 그 부분만 0-3으로 한다.

### [S7.2] 5-2. CMS로 옮기기 (가져온 DB의 CMS 데이터 활용)

- [S7.2-L1] DB를 그대로 가져오므로 Accelerator의 콘텐츠 카탈로그(페이지, 슬롯, 컴포넌트)가 이미 있다.
- [S7.2-L2] Composable Storefront는 CMS 페이지 API로 이를 읽는다. 표준 컴포넌트 타입은 Spartacus에 매핑이 있는 것이 많다.
- [S7.2-L3] **커스텀 CMS 컴포넌트**는 Spartacus의 `cmsComponents` 설정으로 Angular 컴포넌트에 매핑한다(V6). 그 속성이 CMS API 응답에 실리면 새 REST 엔드포인트가 필요 없다.
  - [S7.2-L4] 다만 커스텀 속성 일부가 응답에 나오지 않았다는 커뮤니티 보고가 있다. 어떤 조건에서 응답에 실리는지는 **[미확인]**이다. 커스텀 컴포넌트마다 CMS API 응답을 실제로 확인한다.
- [S7.2-L5] 다만 Accelerator용 페이지 템플릿·슬롯 구성은 Spartacus의 레이아웃 설정과 맞춰야 한다. 그래서 CMS 데이터를 조정하는 작업은 별도로 필요하다(Spartacus 샘플 데이터와 구조가 다르다).
- [S7.2-L6] CMS 컴포넌트 컨트롤러(Accelerator의 `*ComponentController`)가 하던 데이터 조회는 OCC API나 컴포넌트 속성으로 옮긴다(루트의 `sap-commerce-cms-component-controller-migration-review.md` 참고).
- [S7.2-L7] 비활성화 후에는 JSP 렌더링과 컴포넌트 컨트롤러가 동작하지 않는다. 그래서 **사용 중인 커스텀 컴포넌트 타입마다** Spartacus 매핑을 만들지, 콘텐츠에서 뺄지를 정해야 한다.

### [S7.3] 5-3~5-7. 그 밖의 방법

- [S7.3-L1] **5-3. 프런트엔드만으로 처리:** 표시 형식, 정렬, 필터링, 클라이언트 검증, 화면 이동. 보안·가격·재고 판정은 옮기지 않는다(서버에서 다시 검증).
- [S7.3-L2] **5-4. zip의 OCC extension과 Spartacus 기능 라이브러리:** B2B(`b2bocc` 등), 주문 승인, ASM은 짝으로 제공된다. 커스텀 개발 전에 활성화만으로 되는지 확인한다.
- [S7.3-L3] **5-5. 검색 결과 확장:** 목록·검색 결과는 상품 상세와 다른 Converter(색인 속성 + 검색 결과 Populator)를 탄다. Spartacus 검색 endpoint도 명시 목록이므로 fields 설정에 추가해야 한다(V1).
- [S7.3-L4] **5-6. BFF 등 Commerce 밖의 조합 계층:** 이 전제("같은 백엔드에 OCC를 얹는다")와 방향이 달라 우선순위는 낮다.
- [S7.3-L5] **5-7. 이행하지 않기:** Accelerator 전용 동작(JSP 조각 AJAX, 세션 기반 임시 처리, 쓰지 않는 화면)은 대상에서 뺀다. 다만 비활성화 후에는 이행하지 않은 기능이 서비스에서 **없어진다**. 그래서 "이행 불필요"는 업무 쪽의 기능 폐기 승인이 필요한 판정이다.

---

## [S8] 정리: 컨트롤러 메서드 하나를 판정하는 순서

[S8-L1] (코드 블록)
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
   - 업무 규칙 자체의 차이 → D (다른 채널과 병행 기간의 Accelerator 영향 확인)
4. 재사용할 빈이 코어 쪽 컨텍스트에 있는지 확인
5. 스토어프런트 웹 계층·addon에만 있는 것은 비활성화 전에 OCC 쪽으로 옮긴다
```

[S8-L2] 이 저장소의 도구: 1은 staticAnalyzer(`controller-endpoints.csv`, `controller-call-edges.csv`), 2의 Model 속성은 controller-model-analyzer, 3의 표준 OCC 응답·fields는 occ-response-analyzer, 4는 spring-bean-analyzer-from-xml, 활성 extension은 extension-analyzer로 본다. 대응 판정 방법은 루트의 `accelerator-model-vs-occ-response-comparison.md`에 있다.

---

## [S9] 추가 논의가 필요해 보이는 부분

1. [S9-L1] **비활성화 대상 extension의 범위:** 스토어프런트(웹)만인가, addon·accelerator 모듈까지인가? OCC가 쓰는 빈이 들어 있는 extension이 대상에 섞이지 않는지 의존관계로 확인해야 한다.
2. [S9-L2] **병행 기간의 운영 방식:** 두 스토어프런트를 동시에 서비스하는가, 기간은 얼마인가? 이것에 따라 D와 Populator 추가 시의 Accelerator 회귀 확인 범위가 정해진다.
3. [S9-L3] **스토어프런트 웹 계층·addon에만 있는 빈·클래스의 이전 위치:** 폼 Validator, 헬퍼, 인터셉터를 OCC extension에 다시 만들지, 병행 기간을 위해 공용 core extension으로 옮길지.
4. [S9-L4] **CMS 데이터의 조정 범위:** 가져온 콘텐츠 카탈로그를 Spartacus 레이아웃에 맞추는 작업을 이행 범위에 넣는가? 쓰지 않을 Accelerator용 페이지 템플릿·컴포넌트를 정리하는가?
5. [S9-L5] **F의 허용 범위**와 **커스텀 필드 명명 규칙**.
6. [S9-L6] **[미확인] 항목:** 아래 검증 결과의 U1~U4를 2211 소스나 실제 환경으로 확인한다.

---

## [S10] 웹 검색 검증 결과 (2026-11-27)

[S10-L1] SAP 공식 문서(help.sap.com)는 OCC 내부 동작을 자세히 공개하지 않는다. 그래서 Spartacus 공개 소스, SAP KBA, 커뮤니티·공개 예제를 함께 썼다. 근거의 신뢰도를 "근거" 열에 적었다.

[S10-T1] (표)

| ID | 항목 | 확인 결과 | 근거 |
|---|---|---|---|
| V1 | Spartacus 기본 fields | 상품 scope·검색은 명시 목록, 상품 default·장바구니는 `DEFAULT,...`(entries는 명시 목록), 주문 상세는 `FULL`, 주문 내역·사용자는 지정 없음. **이전 판의 "장바구니는 레벨에 넣으면 바로 들어온다"는 장바구니 항목(entries)에는 맞지 않아 수정했다** | Spartacus 공개 소스(`develop`) |
| V2 | 2211 OCC extension | addon에서 extension 방식으로 바뀜. `yocc` 템플릿, 이름은 `occ`로 끝남. `commercewebservices` 웹 컨텍스트에 붙음(컨트롤러 패키지 자동 추가, `resources/occ/v2/<ext>/web/spring/*-web-spring.xml` import). 커스텀 인터셉터는 이 컨텍스트에 다시 등록 | 공개 예제(gist), SAP 커뮤니티 |
| V3 | FieldSetLevelMapping 위치 | OCC 웹 컨텍스트에 정의해야 함(`DefaultFieldSetBuilder`가 같은 컨텍스트에서 로드) | SAP KBA 3427848 |
| V4 | `@RequestMappingOverride` | 패키지, 완전 일치 조건, 우선순위 프로퍼티, 높은 값 우선 | 공개 예제(gist, 제3자). 공식 문서로는 미확인 |
| V5 | 세션 장바구니 | OCC v2 필터 체인에 `BaseSiteMatchingFilter`·`UserMatchingFilter`·`CartMatchingFilter` | 공개된 구버전 `ycommercewebservices` 템플릿 설정. 2211에서 같은지 확인 권장 |
| V6 | 커스텀 CMS 컴포넌트 매핑 | Spartacus `cmsComponents` 설정으로 매핑 | Spartacus 공식 문서 |
| U1 | FieldSetLevelMapping 병합 | 같은 `dtoClass` 두 번째 빈이 합쳐지는지 덮어쓰는지 | 미확인(`DefaultFieldSetLevelHelper.addToLevelMap` 존재만 확인) |
| U2 | OCC 상품 API의 ProductOption | 어떤 옵션으로 Populator가 실행되는지 | 미확인 |
| U3 | 커스텀 CMS 컴포넌트 속성 | 어떤 조건에서 CMS API 응답에 실리는지 | 미확인(일부 속성이 안 나왔다는 커뮤니티 보고만 있음) |
| U4 | 대상 API별 Hook·Strategy | 장바구니·주문 등 확장 지점의 실제 목록 | 미확인 |

### [S10.1] 출처

- [S10.1-L1] [Spartacus default-occ-product-config.ts](https://github.com/SAP/spartacus/blob/develop/core-libs/core/src/occ/adapters/product/default-occ-product-config.ts) / [default-occ-cart-config-factory.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/cart/base/occ/config/default-occ-cart-config-factory.ts) / [default-occ-order-config.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/order/occ/config/default-occ-order-config.ts)
- [S10.1-L2] [Spartacus: Loading Scopes](https://sap.github.io/spartacus-docs/loading-scopes/) / [Customizing CMS Components](https://sap.github.io/spartacus-docs/customizing-cms-components)
- [S10.1-L3] [SAP KBA 3427848](https://userapps.support.sap.com/sap/support/knowledge/en/3427848)(FieldSetLevelMapping의 컨텍스트) / [SAP KBA 2693560](https://userapps.support.sap.com/sap/support/knowledge/en/2693560)(Data Mapping)
- [S10.1-L4] [FieldSetLevelMapping 사용처 (Javadoc 2105)](https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2105/en-US/de/hybris/platform/webservicescommons/mapping/config/class-use/FieldSetLevelMapping.html) / [ProductFacade (Javadoc 2105)](https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2105/en-US/de/hybris/platform/commercefacades/product/ProductFacade.html)
- [S10.1-L5] [공개 예제: 2211 OCC extension과 @RequestMappingOverride (gist)](https://gist.github.com/sperez2-seidor/327f5db98d2d8368e3bb099e6a800929) / [SAP Community: 2211 OCC 컨트롤러 재정의](https://community.sap.com/t5/crm-and-cx-q-a/2211-overriding-occ-a-controller-using-the-extension/qaq-p/12813498) / [SAP Community: ASM 모드에서 커스텀 매핑 미호출](https://community.sap.com/t5/crm-and-cx-q-a/asm-mode-not-calling-custom-request-mapping-for-ccv2-2211-42/qaq-p/14267735) / [SAP Community: 2211 OCC extension의 인터셉터](https://answers.sap.com:443/t5/technology-q-a/how-to-introduce-new-mvc-interceptor-to-occ-extension/qaq-p/13586403)
- [S10.1-L6] [공개 예제: field-mapping 빈 형식 (dev.to)](https://dev.to/aliaksandr_tsviatkou/extending-the-occ-rest-api-in-sap-commerce-custom-endpoints-dtos-and-security-2ehm) / [구버전 ycommercewebservices 템플릿 (GitHub)](https://github.com/gitmadhu/mhybris/blob/master/bin/ext-template/ycommercewebservices/web/webroot/WEB-INF/config/v2/dto-level-mappings-v2-spring.xml)
- [S10.1-L7] SAP 공식 문서 링크(Extending the REST API, Fields Configuration Format 등)는 루트의 `sap-commerce-composable-storefront-migration-discussion.md` 7장에 있다.
