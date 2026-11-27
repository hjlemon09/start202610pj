# [S0] Accelerator 엔드포인트를 OCC 엔드포인트로 어떻게 바꿀 것인가 (제2판)

[S0-L1] 작성일: 2026-11-27

[S0-L2] 이 문서는 변경된 `Question.txt`(논의 주제와 논의의 전제)에 대한 답변이다. 이번 전제에서 가장 큰 변화는 **표준 소스를 수정하지 않고, 표준 API를 바탕으로 할 때는 복사해서 새 클래스를 만든다**는 규칙이다. 이 규칙이 패턴 선택과 작업 방법에 주는 영향을 중심으로 정리했다.

[S0-L3] 패턴 A~H의 정의는 같은 폴더의 축약본(`sap-commerce-accelerator-to-occ-endpoint-migration-patterns-summary.md`)과 같다. `@RequestMappingOverride`의 상세는 `occ-request-mapping-override.md`, Spartacus의 fields 조정은 `spartacus-occ-endpoint-fields-adjustment.md`를 본다.

[S0-L4] 표기: 2211 소스로 직접 확인하지 못한 내용에는 **(확인 필요)**를 붙였다. 코드는 형태를 보이기 위한 예이며 컴파일하지 않았다.

---

## [S1] 전제의 정리

### [S1.1] 전제를 작업 규칙으로 바꾸면

[S1.1-T1] (표)

| 전제 | 작업 규칙 |
|---|---|
| 현행과 같은 방법으로 백엔드를 구성하고 OCC 설정을 추가한다 | facade·service·dao·model·ImpEx는 현행 그대로가 출발점이다 |
| Accelerator 관련 extension은 비활성화한다(파일은 남는다) | Accelerator의 웹 계층(필터~Controller)은 실행되지 않는다. 그 안의 로직은 OCC 쪽으로 옮기지 않으면 사라진다 |
| 작업의 약 90%는 웹 계층(필터~Controller)이다 | 이행 단위는 "Accelerator 컨트롤러 메서드와 필터 하나하나"다 |
| facade·service·dao는 꼭 필요할 때만, model은 근거가 확실할 때만 추가한다 | OCC 컨트롤러는 기존 facade를 부르는 얇은 층으로 만든다 |
| 표준 소스는 그대로 두고, 표준 API를 바탕으로 할 때는 복사해서 새 클래스를 만든다 | 표준 클래스를 직접 고치지 않는다. 바꿀 것은 커스텀 extension에 둔다 |
| 엔드포인트가 표준과 겹치면 표준 extension 비활성화 + 전체 복사, 또는 `@RequestMappingOverride` | 같은 URL을 쓸 때의 방법은 두 가지로 한정된다 |

### [S1.2] 해석이 필요한 점

- [S1.2-L1] "표준 API를 그대로 이용하는 경우는 반드시 복사한다"는 문장은 **표준 API를 바탕으로 무언가를 바꾸는 경우**로 읽는 것이 자연스럽다.
- [S1.2-L2] 아무것도 바꾸지 않는 API(패턴 A, B)까지 복사하면, 같은 처리가 두 벌 생기고 업그레이드 때 따라가야 할 코드만 늘어난다. 이런 API는 **복사하지 않고 표준을 그대로 호출**하는 것을 권한다.
- [S1.2-L3] 또 응답 필드 추가(C)나 업무 로직 변경(D)은 컨트롤러를 복사하지 않고도 설정 추가와 bean 재정의로 할 수 있다(S4.5). 이 방법도 표준 소스를 수정하지 않으므로 전제에 맞는다.
- [S1.2-L4] 따라서 이 문서는 "표준 소스는 수정하지 않는다"를 절대 규칙으로, "복사해서 새 클래스를 만든다"를 **컨트롤러를 바꿔야 할 때의 방법**으로 다룬다. 이 해석이 의도와 다르면 S4의 선택표가 달라지므로 먼저 합의가 필요하다.

---

## [S2] 변환의 큰 흐름

### [S2.1] Accelerator 웹 계층과 OCC의 대응

[S2.1-T1] (표)

| Accelerator 웹 계층 | OCC 쪽 대응 |
|---|---|
| 페이지 컨트롤러(JSP 뷰 반환) | CMS 페이지 API + 여러 OCC API + Spartacus 화면으로 분해(1:N) |
| AJAX 컨트롤러(`@ResponseBody`) | OCC API 하나에 가깝다(1:1) |
| 폼 POST + 리다이렉트 | OCC의 POST/PUT/PATCH + 프런트엔드 라우팅 |
| Form 객체, `@Valid`, `BindingResult` | 요청 WsDTO + OCC Validator, 오류는 `ErrorListWsDTO` |
| `Model`에 담던 Data | 응답 WsDTO(DataMapper + fields) |
| HTTP 세션의 값 | 없음. 요청 파라미터, 장바구니·사용자 속성, 프런트엔드 상태로 옮긴다 |
| Spring Security 폼 로그인, CSRF | OAuth2 토큰. `@Secured`, `@SiteChannelRestriction` |
| 스토어프런트 필터(사이트·세션·장바구니 복원 등) | OCC 필터 체인(BaseSite·User·Cart 매칭 필터 등), 인터셉터 |
| 메시지 키(`GlobalMessages`) | 오류 응답 코드 + 프런트엔드 번역 |

### [S2.2] 엔드포인트별 진행 순서

1. [S2.2-L1] Accelerator의 컨트롤러 메서드와 커스텀 필터를 모두 목록으로 만든다.
2. [S2.2-L2] 메서드마다 부르는 facade, 입력, 출력, 세션 사용, 권한을 적는다.
3. [S2.2-L3] 같은 facade를 부르는 표준 OCC API가 있는지 찾는다.
4. [S2.2-L4] 차이를 보고 패턴(A~H)을 정한다(S3).
5. [S2.2-L5] 패턴이 표준을 바꾸는 것이면 바꾸는 방법(복사, 재정의, 설정 추가)을 정한다(S4).
6. [S2.2-L6] Spartacus 쪽 변경(endpoint 설정, fields, 화면)을 정한다(S6).

---

## [S3] 패턴과 이번 전제의 관계

[S3-T1] (표)

| 패턴 | 표준 소스에 닿는가 | 이번 전제에서의 방법 |
|---|---|---|
| A. 표준 API로 대체 | 닿지 않는다 | 표준을 그대로 쓴다. 복사하지 않는다 |
| B. 표준 API 조합 | 닿지 않는다 | 프런트엔드에서 조합한다 |
| C. 응답 데이터 확장 | 설정만 추가 | `beans.xml`로 WsDTO 속성 추가 + fields 매핑 추가(S4.5) |
| D. 하위 업무 로직 확장 | 설정만 추가 | 커스텀 bean 정의로 Service·Strategy·Populator 교체, Hook 추가(S4.5) |
| E. 요청 계약 확장 | 컨트롤러가 바뀐다 | 표준 컨트롤러를 복사해 바꾼다(S4.2~S4.4) |
| F. 같은 URL 재정의 | 컨트롤러가 바뀐다 | 복사 + `@RequestMappingOverride`, 또는 extension 전체 복사(S4.3, S4.4) |
| G. 신규 엔드포인트 | 닿지 않는다 | 새 컨트롤러 + 기존 facade. 표준을 참고해 복사하는 경우가 많다(S4.2) |
| H. API가 필요 없음 | 닿지 않는다 | CMS, 프런트엔드, 설정 |

[S3-L1] 이번 전제에서 **복사가 실제로 필요한 것은 E, F, 그리고 표준을 바탕으로 만드는 G**다. 나머지는 복사 없이 처리할 수 있다.

---

## [S4] 표준 소스를 수정하지 않고 바꾸는 방법

### [S4.1] 선택지 전체

[S4.1-T1] (표)

| 방법 | URL | 표준 extension | 언제 |
|---|---|---|---|
| ① 복사 + 새 URL | 새 URL | 그대로 | 계약이 표준과 달라지는 경우. 가장 안전 |
| ② 복사 + 같은 URL + `@RequestMappingOverride` | 같은 URL | 그대로 | 같은 URL을 유지해야 하고, 바꾸는 메서드가 몇 개뿐인 경우 |
| ③ 표준 extension 비활성 + extension 전체 복사 | 같은 URL | 비활성 | 그 extension의 API 대부분을 바꾸고, 다른 extension이 그것에 의존하지 않는 경우 |
| ④ 복사 없이 설정·bean 추가 | 같은 URL | 그대로 | 응답 필드 추가(C), 업무 로직 변경(D) |

[S4.1-L1] 권하는 검토 순서는 **④ → ① → ② → ③**이다. 뒤로 갈수록 업그레이드 때 직접 따라가야 할 코드가 늘어난다.

### [S4.2] ① 복사 + 새 URL

- [S4.2-L1] 표준 컨트롤러 메서드를 커스텀 OCC extension의 새 컨트롤러에 복사하고, URL을 바꾼다. 표준 API와 공존하므로 충돌이 없다.
- [S4.2-L2] **클래스 이름:** 같은 이름도 쓸 수 있지만, 패키지만 다르고 단순 클래스명이 같으면 Spring의 기본 bean 이름(예: `cartsController`)이 겹쳐 기동 시 bean 정의 충돌이 날 수 있다. 커스텀 OCC extension은 표준과 같은 웹 컨텍스트(`commercewebservices`)에 올라가기 때문이다. 같은 이름을 쓸 때는 `@Controller("customCartsController")`처럼 bean 이름을 명시한다. (확인 필요)
- [S4.2-L3] **URL 설계:** 표준 경로 아래에 하위 경로를 붙이거나(`/carts/{cartId}/custom-entries`) 업무 이름으로 새 경로를 만든다. 경로 변수 이름은 표준과 맞추면(`baseSiteId`, `userId`, `cartId`) OCC 필터(사이트·사용자·장바구니 매칭)가 그대로 동작한다.
- [S4.2-L4] **프런트엔드:** Spartacus의 endpoint 설정에 새 URL을 추가하고, 그 API를 부르는 Connector/Adapter를 만들어야 한다.
- [S4.2-L5] **복사할 때 같이 가져와야 하는 것:** `@Secured`, `@SiteChannelRestriction`, `@CacheControl`, `@ApiBaseSiteIdAndUserIdParam` 같은 어노테이션, Validator 호출, fields 기본값. 빠뜨리면 권한이나 캐시 동작이 표준과 달라진다.

### [S4.3] ② 복사 + 같은 URL + `@RequestMappingOverride`

- [S4.3-L1] 표준 메서드를 복사해 커스텀 OCC extension의 컨트롤러에 두고, `@RequestMappingOverride`를 붙인다. 같은 URL의 요청은 커스텀 메서드로 간다.
- [S4.3-L2] 이 전제에서는 표준 메서드를 **통째로 복사**하므로, "재정의하면 원래 메서드를 쓸 수 없다"는 문제가 복사로 자연스럽게 해결된다. 복사한 뒤 필요한 부분만 바꾼다.
- [S4.3-L3] 어노테이션 없이 같은 매핑을 두 개 만들면 표준 Spring에서는 기동 시 "Ambiguous mapping" 오류가 난다. 매핑이 조금 다르면 요청 시 "Ambiguous handler methods"가 된다. 매핑은 메서드, 경로, 경로 변수 이름까지 완전히 같게 복사한다.
- [S4.3-L4] 우선순위는 `requestMappingOverride.<클래스 FQN>.<메서드명>.priority`(또는 `priorityProperty`)로 정한다. 같은 URL을 재정의하는 extension이 둘 이상이면 반드시 지정한다.
- [S4.3-L5] 재정의는 메서드 단위다. 같은 클래스의 다른 메서드는 표준이 계속 처리한다. 그래서 "바꾸는 메서드만 복사"하면 된다.
- [S4.3-L6] 상세와 예시는 `occ-request-mapping-override.md`를 본다.

[S4.3-L7] (코드 블록)
```java
// 커스텀 OCC extension (예: customocc)
@Controller("customCartEntriesController")          // bean 이름을 표준과 다르게
@RequestMapping(value = "/{baseSiteId}/users/{userId}/carts")
public class CustomCartEntriesController extends BaseController {

    @RequestMappingOverride                          // 같은 URL을 이쪽이 받는다
    @Secured({ "ROLE_CUSTOMERGROUP", "ROLE_GUEST", "ROLE_TRUSTED_CLIENT" })  // 표준에서 복사
    @RequestMapping(value = "/{cartId}/entries", method = RequestMethod.POST)
    @ResponseBody
    public CartModificationWsDTO createCartEntry(/* 표준과 같은 파라미터 */) {
        // 표준 메서드 본문을 복사하고, 바꿀 부분만 수정한다
    }
}
```

### [S4.4] ③ 표준 extension 비활성 + extension 전체 복사

- [S4.4-L1] 표준 extension을 `localextensions.xml`에서 빼고, 그 extension을 통째로 복사한 커스텀 extension을 대신 넣는다. 같은 URL을 커스텀 쪽이 단독으로 갖게 된다.
- [S4.4-L2] **가장 큰 제약은 의존 관계다.** 다른 extension의 `extensioninfo.xml`이 그 표준 extension을 `requires-extension`으로 요구하면, 표준을 빼는 순간 빌드가 실패한다. 그 의존을 바꾸려면 다른 표준 extension의 파일을 고쳐야 하므로 전제에 어긋난다.
- [S4.4-L3] 특히 `commercewebservices`는 OCC의 웹 컨텍스트(`/occ/v2`) 그 자체이고, `cmsocc`, `b2bocc` 등 대부분의 OCC extension과 커스텀 OCC extension이 이것을 요구하고 이것에 붙어서 동작한다. **`commercewebservices`를 이 방법으로 대체하는 것은 사실상 어렵다**고 본다. (확인 필요)
- [S4.4-L4] 이 방법이 현실적인 것은, 아무도 요구하지 않는 말단의 OCC extension이고 그 API 대부분을 바꿔야 하는 경우뿐이다. 먼저 `extensioninfo.xml`을 검색해 그 extension을 요구하는 extension이 없는지 확인한다.
- [S4.4-L5] **복사할 때의 주의:**
  - [S4.4-L6] extension 이름, 패키지, Spring bean 이름, `project.properties`의 키 접두사를 새 이름으로 바꾼다. 원본이 비활성이라 같은 이름이 남아도 기동은 될 수 있지만, 나중에 원본을 다시 켜면 모두 충돌한다.
  - [S4.4-L7] 복사한 extension에 `items.xml`이 있으면 같은 타입을 두 extension이 정의하게 된다. 이미 DB에 있는 타입의 소유 extension이 바뀌는 셈이므로, 타입 정의는 복사하지 말고 다른 extension에 맡길 수 있는지 먼저 본다. (확인 필요)
  - [S4.4-L8] 복사한 extension의 essential/project data(ImpEx)가 원본과 같은 데이터를 다시 넣지 않는지 확인한다. 현행 DB를 그대로 가져오는 전제라 원본의 데이터는 이미 들어 있을 수 있다.
  - [S4.4-L9] SAP 패치로 원본이 바뀌어도 복사본에는 반영되지 않는다. 복사한 시점의 2211 패치 버전을 기록하고, 업그레이드 때마다 원본과 diff를 본다.

### [S4.5] ④ 복사 없이 설정과 bean 추가로 바꾸기

[S4.5-L1] 아래 방법은 표준 소스를 수정하지 않고, 컨트롤러도 복사하지 않는다. 커스텀 extension에 파일을 **추가**하는 것만으로 표준 API의 동작을 바꾼다.

[S4.5-T1] (표)

| 바꾸려는 것 | 방법 | 비고 |
|---|---|---|
| 응답에 필드 추가(C) | 커스텀 extension의 `beans.xml`에서 WsDTO에 속성 추가 + OCC 웹 컨텍스트에 FieldSetLevelMapping 추가 | Data 쪽 값이 없으면 Populator도 추가 |
| Data에 값 채우기 | Populator를 새로 만들고 기존 Converter의 populator 목록에 추가(modifyPopulatorList 등) | 표준 Converter 정의는 그대로 |
| 업무 로직 변경(D) | 같은 bean 이름(또는 alias)으로 커스텀 Service·Strategy를 정의 | 마지막에 로드된 정의가 이긴다. extension 로드 순서 확인 |
| 장바구니 등의 전후 처리 | Commerce Hook(예: 장바구니 추가 Hook) 추가 | 모든 채널에 적용된다 |
| 입력 검증 추가 | Validator bean 교체 또는 추가 | OCC의 Validator 정의 위치는 2211에서 확인 (확인 필요) |
| 필터 처리 추가 | OCC 필터 체인 목록에 커스텀 필터 추가 | 체인 bean 이름과 병합 방법은 2211에서 확인 (확인 필요) |

[S4.5-L2] 이 방법은 같은 URL과 같은 계약을 유지하므로 Spartacus 쪽은 fields 조정만 필요하거나 변경이 없다. 응답 필드 추가가 기존 화면에 주는 영향은 축약본 S4.1을 본다.

[S4.5-L3] 단, bean 재정의(D)는 OCC만이 아니라 같은 Service를 쓰는 다른 채널(Backoffice, 다른 API, 배치)에도 적용된다. OCC에만 적용하고 싶으면 ②로 컨트롤러에서 처리한다.

### [S4.6] 어느 방법을 고를까

[S4.6-T1] (표)

| 질문 | 예 | 아니오 |
|---|---|---|
| 1. 컨트롤러를 바꾸지 않고 설정·bean 추가로 끝나는가 | ④ | 2로 |
| 2. 계약(URL, 파라미터, 응답 구조)이 표준과 달라지는가 | ① 새 URL | 3으로 |
| 3. 같은 URL을 유지해야 하는 이유가 있는가(Spartacus 표준 기능을 그대로 쓰는 등) | 4로 | ① 새 URL |
| 4. 그 extension을 다른 extension이 요구하지 않고, API 대부분을 바꾸는가 | ③ 전체 복사 | ② `@RequestMappingOverride` |

[S4.6-L1] 같은 URL을 유지하는 이점은 Spartacus의 표준 Connector·Adapter·화면을 그대로 쓸 수 있다는 점이다. 이 이점이 없으면 ①이 가장 단순하다.

---

## [S5] 복사할 때의 공통 규칙

- [S5-L1] **복사 원본 기록:** 클래스 주석이나 별도 목록에 원본 클래스 FQN, 메서드, 2211 패치 버전을 남긴다. 업그레이드 때 원본과의 diff 대상이 된다.
- [S5-L2] **최소 복사:** 클래스 전체가 아니라 바꾸는 메서드만 복사한다. 바꾸지 않는 메서드는 표준이 계속 처리한다(②의 경우).
- [S5-L3] **이름 규칙:** 새 이름을 쓸 때는 `Custom` 접두사 등 팀 규칙을 정한다. 같은 이름을 쓸 때는 패키지와 bean 이름으로 구분한다.
- [S5-L4] **의존 클래스:** 표준 컨트롤러는 `commercewebservices`의 웹 클래스(BaseController, 헬퍼, 상수 등)에 의존한다. 커스텀 OCC extension에서 이것들이 컴파일 경로에 있는지 확인한다. 없으면 필요한 헬퍼도 복사 대상이 된다. (확인 필요)
- [S5-L5] **테스트:** 복사한 API는 표준 API와 같은 요청으로 응답을 비교한다(같은 fields, 같은 사용자 종류: 익명, 로그인, 게스트, ASM).
- [S5-L6] **Swagger:** 표준과 같은 URL의 메서드가 API 문서에 두 번 나오지 않는지, 새 URL이 문서에 나오는지 확인한다.

---

## [S6] 웹 계층(필터~Controller) 이행 체크리스트

### [S6.1] 컨트롤러

- [S6.1-L1] 컨트롤러 안의 로직(facade 호출 전후의 분기, 값 보정, 추가 호출)을 빠짐없이 옮겼는가. Accelerator extension은 비활성화되므로, 옮기지 않은 로직은 사라진다.
- [S6.1-L2] 세션에 넣던 값의 대체 위치(요청 파라미터, 장바구니·사용자 속성, 프런트엔드 상태)를 정했는가.
- [S6.1-L3] 리다이렉트, 뷰 이름, 메시지 키를 JSON 응답과 오류 코드로 바꿨는가.
- [S6.1-L4] 폼 검증(`@Valid`, Form Validator)을 OCC Validator로 옮겼는가.

### [S6.2] 필터와 보안

- [S6.2-L1] Accelerator의 커스텀 필터마다 OCC 필터 체인에 같은 처리가 필요한지 판단했는가. 사이트·언어·통화 설정은 OCC 표준 필터가 하므로 대개 필요 없다.
- [S6.2-L2] 로그인 여부와 권한 확인을 `@Secured`와 OAuth 클라이언트 권한으로 바꿨는가.
- [S6.2-L3] CSRF 토큰 처리는 토큰 인증의 OCC에서는 필요 없다. 대신 CORS 설정(`corsfilter.commercewebservices.*`)을 Spartacus의 도메인에 맞췄는가.
- [S6.2-L4] OAuth 클라이언트(ImpEx로 등록)의 권한과 scope가 Spartacus에서 쓰는 API에 맞는가.

---

## [S7] 프런트엔드(Spartacus) 쪽 영향

[S7-T1] (표)

| 방법 | Spartacus 쪽 작업 |
|---|---|
| ④ 응답 필드 추가 | endpoint `fields`에 필드 추가, 모델 타입 확장(module augmentation). 기존 화면은 영향 없음 |
| ④ 업무 로직 변경 | 대개 없음. 오류 응답이 늘면 오류 처리 추가 |
| ① 새 URL | endpoint 설정 추가, Connector·Adapter·Normalizer, 화면 |
| ② 같은 URL 재정의 | 계약이 같으면 없음. 계약이 바뀌면 그 API를 쓰는 표준 기능 전체를 점검 |
| ③ extension 전체 복사 | ②와 같다 |

[S7-L1] fields 조정의 상세는 `spartacus-occ-endpoint-fields-adjustment.md`를 본다.

[S7-L2] ②와 ③에서 계약을 바꾸면, 같은 API를 부르던 Spartacus의 표준 기능(장바구니, 체크아웃 등)이 모르는 사이에 영향을 받는다. 같은 URL을 유지하면서 계약을 바꾸는 것은 피하고, 계약이 바뀌면 ①을 쓴다.

---

## [S8] 확인이 필요한 사항

- [S8-L1] 표준과 같은 단순 클래스명의 컨트롤러를 커스텀 OCC extension에 두었을 때 bean 이름 충돌이 나는지(S4.2-L2).
- [S8-L2] `commercewebservices`를 요구하는 표준 extension의 목록과, 이를 비활성화했을 때의 빌드 결과(S4.4-L3).
- [S8-L3] extension 전체 복사 시 `items.xml`의 타입 소유 extension이 바뀌는 경우의 시스템 업데이트 동작(S4.4-L7).
- [S8-L4] 표준 컨트롤러가 의존하는 웹 클래스가 커스텀 OCC extension에서 컴파일 가능한지(S5-L4).
- [S8-L5] OCC의 Validator와 필터 체인을 표준 파일 수정 없이 확장하는 bean 정의 방법(S4.5-T1).
- [S8-L6] 위 항목은 2211 소스와 실제 기동으로 확인한 뒤 이 문서에 반영한다.

---

## [S9] 참고

- [S9-L1] [공개 예제: 2211 OCC extension과 @RequestMappingOverride (gist)](https://gist.github.com/sperez2-seidor/327f5db98d2d8368e3bb099e6a800929)
- [S9-L2] [SAP Community: 2211 OCC 컨트롤러 재정의](https://community.sap.com/t5/crm-and-cx-q-a/2211-overriding-occ-a-controller-using-the-extension/qaq-p/12813498)
- [S9-L3] [SAP Community: ASM 모드에서 커스텀 매핑 미호출](https://community.sap.com/t5/crm-and-cx-q-a/asm-mode-not-calling-custom-request-mapping-for-ccv2-2211-42/qaq-p/14267735)
