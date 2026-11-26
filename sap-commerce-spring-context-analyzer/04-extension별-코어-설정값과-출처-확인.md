> SAP Commerce 2211 지정 테넌트의 코어 Spring XML 조사 절차서 — 4/13장. 이전: [3. 지정 테넌트의 활성 extension 집합 확인](03-지정-테넌트의-활성-extension-집합-확인.md) · 다음: [5. 코어 로더의 기본 파일명 및 탐색 분기 확인](05-코어-로더의-기본-파일명-및-탐색-분기-확인.md)

# 4. extension별 코어 설정값과 출처 확인

## 3장에서 확정한 내용

## 4장의 내용

작업 목적. 지정 테넌트의 각 활성 extension에 대해 코어 로더가 읽는 최종 적용 프로퍼티 값을 구한다.

입력. 3장의 활성 목록, 해당 코드가 사용하는 설정 읽기 경로, 대상 배포의 프로퍼티 파일.

출력. 키, 원문 후보, 적용값, 출처, 미설정 또는 값 미확정 여부.

## 배경지식 — 코어 설정값이란

이 장에서 말하는 **코어 설정값**은 각 extension의 **`<extension 이름>.application-context` 프로퍼티 값**이다.
이 값은 그 extension이 **코어 컨텍스트에 어떤 Spring XML 파일을 올리는지**를 정한다.

### "코어"는 Spring 컨텍스트의 한 종류다

SAP Commerce에는 Spring 컨텍스트가 여러 개 있다(4.3절).

|컨텍스트|무엇인가|어떤 XML을 올리는지 정하는 설정|
|---|---|---|
|**코어(core)**|테넌트의 기본 컨텍스트. 서비스, DAO, facade 같은 빈이 대부분 여기에 있다.|`<ext>.application-context`|
|전역(global)|모든 테넌트가 함께 쓰는 컨텍스트|전역용 키 (4.3절)|
|웹(web)|storefront 같은 웹 애플리케이션마다 따로 있는 컨텍스트|각 웹 모듈 `web.xml`의 `contextConfigLocation`|

"코어 설정값"은 이 중 첫 번째, 코어 컨텍스트용 설정의 값이다.

### 예

```properties
# bin/custom/melcocore/project.properties
melcocore.application-context=melcocore-spring.xml,melcocore-overrides.xml
```

"melcocore extension은 코어 컨텍스트에 `melcocore-spring.xml`과 `melcocore-overrides.xml`을 올린다"는 뜻이다.

### 이 장이 하는 일

같은 키가 여러 파일에 있을 수 있으므로, **최종으로 적용되는 값과 그 값이 어디서 왔는지(출처)**를 정한다.

- 키가 있을 수 있는 곳: extension의 `project.properties`, `bin/platform/project.properties`, `config/local.properties`, 테넌트별 설정 파일 등(4.2절)
- 최종값은 5장에서 XML 파일 목록으로 나눈다.
- 키가 없거나 값이 비어 있으면, 기본 파일명 규칙(`<ext>-application-context.xml`)이 적용되는지 5장에서 판단한다.

## 4.1 실제 키와 조회 메서드를 식별한다

1. 대상 extension 이름이 melcocore면 melcocore.application-context를 정확한 문자열로 검색한다.
2. 3장에서 찾은 코드에서 실제로 .application-context가 어떻게 조합되는지 확인한다.
3. getParameter(key), getString(key, default) 또는 다른 접근 경로 중 무엇을 호출하는지 기록한다.
4. 값을 읽는 객체가 특정 테넌트의 설정인지, 코어 로더에 전달된 별도 환경인지 확인한다.
5. fallback 값을 메서드 인수로 전달한다면, 원본 키의 존재 여부와 fallback 적용 결과를 따로 적는다.
6. 키나 값이 전역 컨텍스트와 테넌트 코어에서 공용 메서드로 해석된다면, 실제 호출 인수를 확인한다.

## 4.2 프로퍼티의 후보와 최종값을 구한다

아래 위치를 열어 동일한 키를 찾는다.
키가 발견되면 파일 경로와 줄, 원문의 값, 이 파일이 대상 배포에서 적용되는 근거를 기록한다.

|탐색 위치                                                   |확인할 내용                    |
|--------------------------------------------------------|--------------------------|
|`<extension>/project.properties`                        |extension이 제공하는 기본값       |
|`HYBRIS_HOME/bin/platform/project.properties`           |플랫폼 기본 설정                 |
|다른 활성 extension의 `project.properties`                   |동일 키를 정의하는 추가 후보          |
|`CONFIG_DIR/local.properties`                           |현재 설치의 오버라이드              |
|`tenant_<ID>.properties`, `local_tenant_<ID>.properties`|대상 구현이 이 설정층을 읽을 때 테넌트별 후보|
|환경별 설정과 배포 생성 결과                                        |추가 설정 파일, 외부 값, 배포 시 치환된 값|
|대상 설정 합성 코드                                             |적용 순서, 같은 층에서의 충돌 처리, 기본값 |

1. SAP 문서의 일반 프로퍼티 우선순위를 출발점으로 삼는다.
2. 여러 extension 파일이 같은 키를 정의하면 해당 배포의 설정 합성 순서를 확인한다.
3. 실행 설정이 환경 변수나 JVM 시스템 프로퍼티를 넘긴다면, 값이 존재한다는 사실에 이어 대상 조회 메서드가 실제로 이를 읽는지 확인한다.
4. 최종 설정을 생성하는 파일이 따로 있다면 원본 템플릿과 생성 결과를 연결한다.
5. 주석, 줄 이어쓰기, 구분자, 이스케이프가 결과에 영향을 주는 값은 실제 프로퍼티 파서의 규칙을 적용한다.
6. 치환 전 원문과 치환 후 값, 치환에 사용된 값을 각각 기록한다.
7. XML 이름에 연결되는 환경 값이 미확정이면 가능한 문자열이나 부족한 입력을 남긴다.

## 4.3 코어, 전역, 웹의 설정 키를 식별한다

• <extension>.application-context는 조사하고 있는 코어 키다.
• <extension>.global-application-context가 보이면 전역 컨텍스트의 후보로 표시하고 실제 소비 코드를 확인한다.
• 앱이나 서블릿의 contextConfigLocation이 보이면 해당 웹 컨텍스트의 후보로 표시한다.
• XML의 파일명에 core 또는 web이 들어 있더라도, 실제 로더와 레지스트리가 어느 쪽인지 확인해 분류한다.
• 같은 XML 리소스가 여러 컨텍스트에서 사용되면 각각의 로드 발생 지점과 컨텍스트를 기록한다.

## 4.4 결과별 판정을 기록한다

|확인 결과                                              |기록                                |
|---------------------------------------------------|----------------------------------|
|최종값이 `melcocore-spring.xml,melcocore-overrides.xml`|문자열 전체와 선언 출처를 기록하고 5장에서 분해 방식을 확인|
|적용 파일 전체에서 키가 없음을 확인함                              |`미설정`으로 기록하고 5장에서 기본 분기 확인        |
|최종값이 빈 문자열 또는 공백임                                  |해당 원문을 기록하고 5장에서 미설정과 같은 분기인지 확인  |
|후보 파일이 일부 누락되어 최종값을 고를 수 없음                        |후보와 누락된 배포 설정을 기록                 |
|다른 컨텍스트용 키만 발견됨                                    |해당 키를 별도 메모로 남기고 코어 키의 미설정 여부를 판정 |

완료 기준. 활성 extension마다 최종값과 그 출처가 있다. 미설정과 미확정이 구분되며, 동일 키의 경쟁 후보를 처리한 근거가 있다.

SAP Help — Where to Define Property Values
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8b8e13c9866910149d40b151a9196543.html

## 4.5 프로그램에서의 처리 (spring-bean-analyzer-from-xml)

4장의 처리는 [spring-bean-analyzer-from-xml](../javaTool/spring-bean-analyzer-from-xml/README.md)이 한다. extension-analyzer는 4장에 관여하지 않는다.

### 구성 위치

|소스|하는 일|
|---|---|
|`ImportPropertyResolver.java`|프로퍼티 파일을 읽어 합치고, 키의 최종값과 출처를 구한다. `${key}`, `${key:기본값}` 치환도 여기서 한다.|
|`SpringLoadingAnalyzer.java`|extension마다 `<ext>.application-context`(코어), `<ext>.global-context`(전역), `web.xml`의 `contextConfigLocation`(웹)을 조회해 진입 XML 후보를 만든다.|

### 읽는 프로퍼티 파일과 우선순위

|파일|읽는 조건|우선순위|
|---|---|---|
|`config/local.properties`|항상 (`-Danalyzer.localproperties=<파일>`로 변경 가능)|1 (가장 높음)|
|각 extension의 `project.properties`|**선택(활성)으로 판정된 extension만**|2|
|`bin/platform/project.properties`|항상|3|

- 여러 extension의 `project.properties`가 같은 키에 **서로 다른 값**을 정의하면 하나를 고르지 않고 해석 실패로 둔다. 같은 값이면 그 값을 쓴다.
- extension 선택 판정이 불완전하면(`selection_complete=false`) `local.properties`에 없는 키는 해석 실패로 둔다. 빠진 extension에 같은 키가 있을 수 있기 때문이다.
- 파일은 JDK의 `java.util.Properties` 규칙(주석, 줄 이어쓰기, 이스케이프)으로 읽는다.

### 4장의 절과의 대응

|4장의 절|프로그램에서 하는가|내용|
|---|---|---|
|4.1 실제 키와 조회 메서드를 식별한다|**일부**|키는 `<ext>.application-context`, `<ext>.global-context`로 **고정**해 조회한다. SAP 코드에서 키를 어떻게 조합하는지, 어떤 메서드(`getParameter`, `getString` 등)로 읽는지, fallback 인수가 있는지는 확인하지 않는다.|
|4.2 프로퍼티의 후보와 최종값을 구한다|**일부**|위 세 파일을 위 우선순위로 합쳐 최종값을 구하고, 출처를 남긴다. `tenant_<ID>.properties`, `local_tenant_<ID>.properties`, 환경별 설정, 배포 시 생성·치환되는 설정, 환경 변수, JVM 시스템 프로퍼티는 **읽지 않는다.** 실제 SAP의 설정 합성 순서와 일치하는지도 확인하지 않았다.|
|4.3 코어, 전역, 웹의 설정 키를 식별한다|**함**|`<ext>.application-context`는 `CORE`, `<ext>.global-context`는 `GLOBAL`, `web.xml`의 `contextConfigLocation`과 서블릿 설정은 `WEB`/`SERVLET` 컨텍스트로 분류한다. 파일명이 아니라 어느 키·설정에서 왔는지로 분류한다. 웹은 `<webmodule>`이 선언된 extension만 대상이다. (본문의 `<extension>.global-application-context`와 프로그램의 `<ext>.global-context` 중 어느 쪽이 맞는지는 확인하지 않았다.)|
|4.4 결과별 판정을 기록한다|**일부**|값이 있으면 쉼표·세미콜론·공백으로 나눈 토큰마다 진입 후보를 만든다(`DECLARED_PROPERTY`). 키가 없으면 5장의 기본 파일명 규칙으로 넘어간다. 치환 실패나 extension 간 값 충돌은 `UNRESOLVED_LOADING_PROPERTY`로 기록한다. 값이 빈 문자열이거나 구분자만 있으면 진입 후보를 만들지 않고 `EMPTY_LOADING_PROPERTY`로 기록한다(키가 있으므로 기본 파일명 규칙도 적용하지 않는다). 일부 파일이 빠져 최종값을 고를 수 없는 경우는 따로 구분하지 않는다.|

### 결과물

|파일|내용|
|---|---|
|`spring-loading-entrypoints.csv`|진입 XML 후보. `configuration`(설정 원문), `target_xml`(진입 XML), `property_evidence`(값의 출처: 어느 파일의 어느 키), `modeled_context`(컨텍스트)|
|`spring-loading-issues.csv`|`UNRESOLVED_LOADING_PROPERTY`(치환 실패·충돌), `EMPTY_LOADING_PROPERTY`(값이 비어 있음), `PROPERTY_FILE_ERROR`(파일을 읽지 못함), `INCOMPLETE_SELECTION` 등|

## 5장에서 사용할 내용
