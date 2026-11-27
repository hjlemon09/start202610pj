# [S0] Accelerator 엔드포인트를 OCC 엔드포인트로 어떻게 바꿀 것인가 (제2판)

[S0-L1] 작성일: 2026-11-27

[S0-L2] 이 문서는 `Question.txt`(논의 주제와 논의의 전제)에 대한 답변이다. 이번 전제의 핵심은 **표준 소스를 수정하지 않고, 표준 extension(Aextension)을 통째로 복사해 `/bin/custom`에 새 extension(Mextension)을 만든 뒤, 두 extension의 충돌을 막는다**는 규칙이다. 이 규칙의 작업 방법과 주의점을 중심으로 정리했다.

[S0-L3] 패턴 A~H의 정의는 같은 폴더의 축약본(`sap-commerce-accelerator-to-occ-endpoint-migration-patterns-summary.md`)과 같다. `@RequestMappingOverride`의 상세는 `occ-request-mapping-override.md`, Spartacus의 fields 조정은 `spartacus-occ-endpoint-fields-adjustment.md`를 본다.

[S0-L4] 표기: 2211 소스로 직접 확인하지 못한 내용에는 **(확인 필요)**를 붙였다. 코드와 설정은 형태를 보이기 위한 예이며 실행하지 않았다.

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
| 표준 소스는 그대로 두고 복사한다 | `/bin/platform`, `/bin/modules`의 파일은 한 줄도 고치지 않는다 |
| Aextension을 복사해 `/bin/custom`에 Mextension을 만든다 | 바꿀 것은 모두 Mextension에서 바꾼다 |
| Aextension과 Mextension의 충돌을 막는다 | 방법은 (1) Aextension 비활성화, (2) 둘 다 활성 + 엔드포인트를 겹치지 않게, 의 두 가지가 기본이다(S4) |

### [S1.2] 전제에 대한 의견

- [S1.2-L1] extension 전체 복사는 **바꾸는 API가 그 extension에 많을 때** 효율적이다. 바꾸는 API가 몇 개뿐이면, 복사본 전체를 업그레이드 때마다 따라가야 하는 비용이 더 크다.
- [S1.2-L2] 그래서 Aextension마다 "바꾸는 API의 수"를 먼저 세고, 적으면 S4.5의 가벼운 방법(필요한 컨트롤러만 커스텀 OCC extension에 두기, 설정·bean 추가)도 같이 검토하기를 권한다. 이 방법들도 표준 소스를 수정하지 않으므로 전제에는 어긋나지 않는다.
- [S1.2-L3] 또 바꾸지 않는 API(패턴 A, B)만 쓰는 extension은 복사하지 않고 표준을 그대로 쓴다.

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
3. [S2.2-L3] 같은 facade를 부르는 표준 OCC API와, 그 API가 들어 있는 표준 extension(Aextension 후보)을 찾는다.
4. [S2.2-L4] 차이를 보고 패턴(A~H)을 정한다(S3).
5. [S2.2-L5] Aextension별로 바꾸는 API를 모아, 복사 여부와 충돌 방지 방법을 정한다(S4).
6. [S2.2-L6] Spartacus 쪽 변경(endpoint 설정, fields, 화면)을 정한다(S7).

---

## [S3] 패턴과 이번 전제의 관계

[S3-T1] (표)

| 패턴 | Aextension 복사가 필요한가 | 이번 전제에서의 방법 |
|---|---|---|
| A. 표준 API로 대체 | 필요 없다 | 표준을 그대로 쓴다 |
| B. 표준 API 조합 | 필요 없다 | 프런트엔드에서 조합한다 |
| C. 응답 데이터 확장 | 대개 필요 없다 | WsDTO 속성 + fields 매핑 추가로 된다(S4.5). 복사본 안에서 해도 된다 |
| D. 하위 업무 로직 확장 | 대개 필요 없다 | Service·Strategy·Populator 교체, Hook 추가(S4.5) |
| E. 요청 계약 확장 | 필요하다 | Mextension의 컨트롤러에서 바꾼다 |
| F. 같은 URL 재정의 | 필요하다 | Mextension에서 바꾸고, (1) A 비활성화 또는 S4.4의 `@RequestMappingOverride` |
| G. 신규 엔드포인트 | 경우에 따라 | Aextension과 성격이 같은 API면 Mextension에 추가한다. 다르면 별도 커스텀 OCC extension |
| H. API가 필요 없음 | 필요 없다 | CMS, 프런트엔드, 설정 |

[S3-L1] 복사가 실제로 필요한 것은 **E, F, 그리고 Aextension 안에 넣을 G**다. 어느 Aextension을 복사할지는 이 패턴들에 해당하는 API가 어느 extension에 있는지로 정해진다.

---

## [S4] Aextension 복사와 충돌 방지

### [S4.1] 복사 작업

[S4.1-L1] Aextension은 대개 템플릿이 아니라서 `extgen`으로 새 이름의 extension을 만들 수 없다. 폴더를 복사한 뒤 이름을 직접 바꾼다. (확인 필요: 대상 extension이 템플릿으로 쓸 수 있는지)

[S4.1-T1] (표)

| 바꿀 곳 | 내용 |
|---|---|
| 폴더 | `/bin/modules/.../aextension` → `/bin/custom/mextension` |
| `extensioninfo.xml` | `extension name`, `classprefix`, `managername`, `jaloLogicalName`, webmodule의 `webroot` |
| 파일 이름 | `aextension-spring.xml`, `-items.xml`, `-beans.xml`, `-web-spring.xml`, `buildcallbacks.xml`의 프로젝트명 |
| 리소스 경로 | `resources/aextension/`, OCC extension이면 `resources/occ/v2/aextension/` 같은 extension 이름이 들어간 경로 |
| `project.properties` | `aextension.` 로 시작하는 키 |
| Java 패키지·클래스 | 패키지, `AextensionConstants` 등 extension 이름이 들어간 클래스 |
| Spring bean 이름 | XML의 `id`, `alias`, `@Controller("...")`, `@Component("...")` |
| `localextensions.xml` | Mextension 추가 (Aextension 처리는 S4.2, S4.3) |

[S4.1-L2] 복사 후 `ant clean all`과 시스템 업데이트를 하고, 바꾸기 전 상태로 Aextension과 같은 응답이 나오는지 먼저 확인한다. 그 다음에 Mextension의 내용을 바꾼다.

[S4.1-L3] 복사 원본의 FQN과 2211 패치 버전을 Mextension의 README 등에 기록한다. SAP 패치로 Aextension이 바뀌어도 Mextension에는 반영되지 않으므로, 업그레이드 때마다 원본과 diff를 본다.

### [S4.2] 방법 (1) Aextension 비활성화

[S4.2-L1] `localextensions.xml`에서 Aextension을 빼고 Mextension만 켠다. 같은 URL, 같은 이름의 클래스와 bean을 Mextension이 단독으로 가지므로 이름 충돌을 신경 쓰지 않아도 된다. 같은 URL을 유지하므로 Spartacus의 표준 기능을 그대로 쓸 수 있다.

- [S4.2-L2] **가장 큰 제약은 의존 관계다.** 다른 extension의 `extensioninfo.xml`이 Aextension을 `requires-extension`으로 요구하면, Aextension을 빼는 순간 빌드가 실패한다. 그 의존을 Mextension으로 바꾸려면 다른 표준 extension의 파일을 고쳐야 하므로 전제에 어긋난다.
- [S4.2-L3] 따라서 이 방법은 **다른 extension이 요구하지 않는 extension**에만 쓸 수 있다. 먼저 `/bin` 아래의 모든 `extensioninfo.xml`에서 `requires-extension name="aextension"`을 검색한다.
- [S4.2-L4] 특히 `commercewebservices`는 OCC의 웹 컨텍스트(`/occ/v2`) 자체이고, `cmsocc`, `b2bocc` 등 대부분의 OCC extension과 커스텀 OCC extension이 이것을 요구하고 이것에 붙어서 동작한다. **`commercewebservices`를 방법 (1)로 대체하는 것은 사실상 어렵다**고 본다. (확인 필요)
- [S4.2-L5] **items.xml:** Aextension이 타입을 정의하면, 비활성화 후에는 Mextension이 같은 타입을 정의해야 한다. 현행 DB에는 그 타입이 Aextension 소유로 들어 있으므로, 시스템 업데이트에서 소유 extension이 바뀌는 동작을 검증한다. (확인 필요)
- [S4.2-L6] **ImpEx:** 현행 DB를 그대로 가져오는 전제라 Aextension의 essential/project data는 이미 들어 있을 수 있다. Mextension의 같은 ImpEx가 데이터를 다시 넣거나 덮어쓰지 않는지 확인한다.
- [S4.2-L7] **이름:** 비활성 상태라 같은 패키지·bean 이름이 남아도 기동은 될 수 있다. 하지만 Aextension을 다시 켜면 모두 충돌하므로, S4.1-T1의 이름 변경은 그대로 하기를 권한다.

### [S4.3] 방법 (2) 둘 다 활성 + 엔드포인트를 겹치지 않게

[S4.3-L1] Aextension과 Mextension을 둘 다 켜 두고, Mextension 쪽 엔드포인트를 새 URL로 바꾼다. 의존 관계는 그대로라 빌드 문제는 없지만, **엔드포인트 외에도 겹치는 것이 많다.**

[S4.3-T1] (표)

| 겹치는 것 | 그대로 두면 | 조치 |
|---|---|---|
| 엔드포인트(URL + 메서드) | 기동 시 "Ambiguous mapping", 또는 요청 시 "Ambiguous handler methods" | Mextension의 URL을 바꾼다(S4.3-L2) |
| Java 패키지·클래스(FQN) | 같은 FQN의 클래스가 두 개 클래스패스에 올라가 어느 쪽이 로드될지 알 수 없다 | 패키지를 바꾼다 |
| Spring bean 이름 | 나중에 로드된 정의가 앞의 것을 **조용히 덮어쓴다**. Aextension의 bean이 의도치 않게 바뀐다 | 모든 bean 이름을 바꾼다 |
| `items.xml`의 타입 | 같은 타입 코드를 두 번 정의해 빌드나 시스템 업데이트가 실패한다 | Mextension에서 `items.xml`의 타입 정의를 지운다(Aextension의 타입을 그대로 쓴다) |
| `beans.xml`의 Data·WsDTO | 같은 클래스를 두 extension이 정의해 병합된다. 속성 타입이 다르면 생성 오류 | 바꾸지 않는 bean 정의는 지운다. 추가 속성만 남긴다 |
| `webroot`(웹 모듈) | 같은 컨텍스트 경로로 기동이 실패한다 | 다른 webroot를 쓴다(S4.3-L4) |
| `project.properties` 키 | 같은 키를 두 extension이 다른 값으로 정의해 어느 값이 쓰일지 헷갈린다 | 키 접두사를 바꾼다 |
| ImpEx(essential/project data) | 같은 데이터가 두 번 들어가거나 서로 덮어쓴다 | Mextension에서 지우거나 새 데이터만 남긴다 |
| FieldSetLevelMapping, Swagger 설정 | 같은 WsDTO에 매핑이 두 개 생기거나 API 문서가 섞인다 | 바꾸는 것만 남긴다 |

- [S4.3-L2] **URL 설계:** 경로에 구분자를 넣는다(예: `/{baseSiteId}/custom/carts/...`). 경로 변수 이름은 표준과 맞추면(`baseSiteId`, `userId`, `cartId`) OCC 필터(사이트·사용자·장바구니 매칭)가 그대로 동작한다.
- [S4.3-L3] **복사본 정리:** 이 방법에서는 Mextension에 **바꾸는 API와 새 API만 남기고 나머지는 지운다**. 바꾸지 않는 API까지 남기면 같은 기능이 두 URL로 생기고, S4.3-T1의 충돌 대상만 늘어난다. 결과적으로 Mextension은 "Aextension의 일부를 복사한 커스텀 extension"이 된다.
- [S4.3-L4] **웹 모듈인 Aextension(예: `commercewebservices`):** Mextension이 별도 webroot(예: `/customocc`)의 별도 웹 애플리케이션이 된다. 필터 체인, 보안 설정, CORS가 따로 생기고, Spartacus는 기본 prefix(`/occ/v2/`) 밖의 URL을 불러야 한다. Spartacus에서 endpoint별로 다른 prefix를 쓰는 설정 방법은 확인이 필요하다. (확인 필요)
- [S4.3-L5] **OCC extension인 Aextension(예: `cmsocc` 같은, `commercewebservices`에 붙는 extension):** Mextension도 같은 OCC 웹 컨텍스트에 붙는다. 같은 컨텍스트이므로 URL과 bean 이름의 충돌 관리가 특히 중요하다. (확인 필요: OCC extension을 웹 컨텍스트에 붙이는 설정이 extension 이름에 의존하는지)
- [S4.3-L6] **프런트엔드:** 새 URL이므로 Spartacus의 endpoint 설정, Connector·Adapter, 화면을 바꿔야 한다. Spartacus 표준 기능(장바구니, 체크아웃 등)을 그대로 쓰는 API라면 이 변경이 크다.

### [S4.4] 방법 (3) 둘 다 활성 + 같은 URL을 `@RequestMappingOverride`로

[S4.4-L1] 전제의 "등"에 해당하는 세 번째 방법이다. 방법 (2)처럼 둘 다 켜 두되, 바꾸는 메서드에 `@RequestMappingOverride`를 붙여 **같은 URL을 Mextension 쪽이 받게** 한다.

- [S4.4-L2] 방법 (1)을 쓸 수 없는 extension(다른 extension이 요구하는 `commercewebservices` 등)에서, 같은 URL을 유지해야 할 때 현실적인 방법이다.
- [S4.4-L3] Aextension의 메서드를 복사한 뒤 붙이므로 "재정의하면 원래 메서드를 쓸 수 없다"는 문제는 복사로 해결된다. 매핑은 메서드, 경로, 경로 변수 이름까지 완전히 같아야 한다.
- [S4.4-L4] 재정의는 메서드 단위다. Mextension에는 바꾸는 메서드만 남기고, 나머지는 Aextension이 계속 처리한다. S4.3-T1의 엔드포인트 외 충돌 조치는 그대로 필요하다.
- [S4.4-L5] Spartacus 쪽은 계약이 같으면 변경이 없다. 계약을 바꾸면 그 API를 쓰는 표준 기능 전체를 점검한다.
- [S4.4-L6] 상세와 예시는 `occ-request-mapping-override.md`를 본다.

### [S4.5] 보완: 복사 없이 바꾸는 방법

[S4.5-L1] 바꾸는 API가 적은 경우나 C, D 패턴에는 아래 방법이 더 가볍다. 커스텀 extension에 파일을 **추가**할 뿐 표준 소스는 수정하지 않는다.

[S4.5-T1] (표)

| 바꾸려는 것 | 방법 | 비고 |
|---|---|---|
| 응답에 필드 추가(C) | 커스텀 extension의 `beans.xml`에서 WsDTO에 속성 추가 + OCC 웹 컨텍스트에 FieldSetLevelMapping 추가 | Data 쪽 값이 없으면 Populator도 추가 |
| Data에 값 채우기 | Populator를 새로 만들고 기존 Converter의 populator 목록에 추가 | 표준 Converter 정의는 그대로 |
| 업무 로직 변경(D) | 같은 bean 이름(또는 alias)으로 커스텀 Service·Strategy를 정의 | 다른 채널(Backoffice, 배치)에도 적용된다 |
| 장바구니 등의 전후 처리 | Commerce Hook 추가 | 모든 채널에 적용된다 |
| 컨트롤러 몇 개만 변경 | 필요한 메서드만 커스텀 OCC extension에 복사(새 URL 또는 `@RequestMappingOverride`) | extension 전체 복사보다 따라갈 코드가 적다 |

### [S4.6] 어느 방법을 고를까

[S4.6-T1] (표)

| 질문 | 예 | 아니오 |
|---|---|---|
| 1. 바꾸는 API가 그 extension에 많은가 | 2로 | S4.5 검토 |
| 2. Aextension을 다른 extension이 `requires-extension`으로 요구하지 않는가 | 3으로 | 4로 |
| 3. 같은 URL을 유지해야 하는가(Spartacus 표준 기능을 그대로 쓰는 등) | (1) 비활성화 | (1) 또는 (2) |
| 4. 같은 URL을 유지해야 하는가 | (3) `@RequestMappingOverride` | (2) 새 URL |

[S4.6-L1] `commercewebservices`의 API(장바구니, 사용자, 주문 등 대부분의 표준 API)를 바꾸는 경우는 질문 2에서 "아니오"가 되므로, **방법 (2) 또는 (3)**이 된다.

---

## [S5] 복사할 때의 공통 규칙

- [S5-L1] **복사 원본 기록:** 원본 extension 이름, 클래스 FQN, 2211 패치 버전을 남긴다. 업그레이드 때 원본과의 diff 대상이 된다.
- [S5-L2] **이름 규칙:** extension 이름, 패키지, bean 이름, 프로퍼티 접두사에 쓸 규칙(예: `custom` 접두사)을 팀에서 정한다.
- [S5-L3] **최소 유지:** 방법 (2), (3)에서는 Mextension에 바꾸는 것과 새것만 남긴다.
- [S5-L4] **의존 클래스:** 복사한 컨트롤러가 Aextension의 웹 클래스(BaseController, 헬퍼, 상수 등)에 의존하면, Mextension의 컴파일 경로에 그것이 있는지 확인한다. 웹 클래스는 다른 extension에서 보이지 않을 수 있다. (확인 필요)
- [S5-L5] **테스트:** 바꾸기 전에 Mextension이 Aextension과 같은 응답을 내는지 확인하고(S4.1-L2), 바꾼 뒤에는 사용자 종류(익명, 로그인, 게스트, ASM)별로 테스트한다.
- [S5-L6] **복사할 때 같이 가져와야 하는 것:** `@Secured`, `@SiteChannelRestriction`, `@CacheControl` 같은 어노테이션, Validator 호출, fields 기본값. 빠뜨리면 권한이나 캐시 동작이 표준과 달라진다.

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
- [S6.2-L3] CSRF 토큰 처리는 토큰 인증의 OCC에서는 필요 없다. 대신 CORS 설정(`corsfilter.commercewebservices.*`)을 Spartacus의 도메인에 맞췄는가. 방법 (2)에서 별도 webroot를 쓰면 그쪽 CORS도 설정한다.
- [S6.2-L4] OAuth 클라이언트(ImpEx로 등록)의 권한과 scope가 Spartacus에서 쓰는 API에 맞는가.

---

## [S7] 프런트엔드(Spartacus) 쪽 영향

[S7-T1] (표)

| 방법 | Spartacus 쪽 작업 |
|---|---|
| (1) Aextension 비활성화 | 계약이 같으면 없음. 계약을 바꾸면 그 API를 쓰는 표준 기능 전체를 점검 |
| (2) 새 URL | endpoint 설정 추가, Connector·Adapter·Normalizer, 화면. 별도 webroot면 prefix 설정도 |
| (3) `@RequestMappingOverride` | (1)과 같다 |
| 보완: 응답 필드 추가 | endpoint `fields`에 필드 추가, 모델 타입 확장(module augmentation). 기존 화면은 영향 없음 |

[S7-L1] fields 조정의 상세는 `spartacus-occ-endpoint-fields-adjustment.md`를 본다.

[S7-L2] 같은 URL을 유지하면서 계약을 바꾸면, 같은 API를 부르던 Spartacus의 표준 기능이 모르는 사이에 영향을 받는다. 계약이 바뀌면 방법 (2)를 쓰는 것을 권한다.

---

## [S8] 확인이 필요한 사항

- [S8-L1] 복사 대상 extension이 `extgen` 템플릿으로 쓸 수 있는지(S4.1-L1).
- [S8-L2] `commercewebservices`를 요구하는 표준 extension의 목록과, 이를 비활성화했을 때의 빌드 결과(S4.2-L4).
- [S8-L3] Aextension 비활성화 후 Mextension이 같은 타입을 정의할 때의 시스템 업데이트 동작(S4.2-L5).
- [S8-L4] 웹 모듈을 별도 webroot로 복사했을 때 Spartacus에서 그 URL을 부르는 설정 방법(S4.3-L4).
- [S8-L5] OCC extension을 OCC 웹 컨텍스트에 붙이는 설정이 extension 이름에 의존하는지(S4.3-L5).
- [S8-L6] 복사한 컨트롤러가 의존하는 웹 클래스가 Mextension에서 컴파일 가능한지(S5-L4).
- [S8-L7] 위 항목은 2211 소스와 실제 기동으로 확인한 뒤 이 문서에 반영한다.

---

## [S9] 참고

- [S9-L1] [공개 예제: 2211 OCC extension과 @RequestMappingOverride (gist)](https://gist.github.com/sperez2-seidor/327f5db98d2d8368e3bb099e6a800929)
- [S9-L2] [SAP Community: 2211 OCC 컨트롤러 재정의](https://community.sap.com/t5/crm-and-cx-q-a/2211-overriding-occ-a-controller-using-the-extension/qaq-p/12813498)
- [S9-L3] [SAP Community: ASM 모드에서 커스텀 매핑 미호출](https://community.sap.com/t5/crm-and-cx-q-a/asm-mode-not-calling-custom-request-mapping-for-ccv2-2211-42/qaq-p/14267735)
