SAP Commerce 2211 테넌트별 코어 Spring XML 조사 절차서

0. 목적과 적용 범위

현행 Accelerator의 PDP를 제공하는 SAP Commerce 배포에서 테넌트 하나를 식별한다. 그 테넌트에 활성화된 extension을 찾고, 코어 Spring 로더가 직접 여는 진입 XML과 Spring XML의 <import>로 여는 하위 XML을 실제 리소스 경로 및 처리 순서와 함께 기록한다.

이 절차는 Windows 로컬의 SAP Commerce 2211 계열과 IntelliJ를 기준으로 작성했다. 조사에는 소스, 설정, 빌드·배포 산출물, 대상 버전 JAR의 디컴파일 결과를 사용한다. 서버 기동이나 디버그 브레이크포인트는 전제하지 않는다. 확인 위치에 제시된 경로는 기본 구조 또는 탐색 출발점이다. 실제 프로젝트에서 확인한 경로를 결과에 기록한다.

완료 범위는 코어 XML의 로드 계획이다. XML 이후 Java 설정, 컴포넌트 스캔, 레지스트리 후처리에서 추가·변경되는 빈과 팩토리가 반환하는 실제 객체는 후속 분석 항목이다. Global ApplicationContext와 Storefront WebApplicationContext의 설정을 발견하면 컨텍스트 이름을 표시해 코어 목록과 구별한다.

목차

1. 대상 배포와 테넌트 ID
2. 배포에 포함된 extension 목록
3. 대상 테넌트에 활성화된 extension 목록
4. extension별 코어 Spring 설정의 최종값
5. 코어 로더의 기본값과 리소스 선택 규칙
6. 진입 XML의 실제 파일 또는 JAR 엔트리
7. extension 사이의 로드 순서
8. 동일 extension 안의 진입 XML 순서
9. XML 내부 import 처리 순서
10. 최종 산출물과 완료 판정
11. 참고 문서

조사 기록 방법

• HYBRIS_HOME은 이 문서에서 bin이 들어 있는 실제 설치 디렉터리를 뜻한다. CONFIG_DIR은 해당 배포가 실제로 읽는 설정 디렉터리다.
• 파일 확인 결과는 경로 + XML 요소·프로퍼티 키·줄 번호로 기록한다. 줄 번호가 없는 디컴파일 결과는 대상 JAR 경로 + 클래스 전체 이름 + 메서드 시그니처 + 판정에 사용한 분기로 기록한다.
• 소스 탐색에는 IntelliJ의 Find in Files를 쓰고, 검색 범위에 대상 설치의 모듈을 포함한다. Java 구현이 소스에 없으면 해당 배포의 JAR를 설치에 연결한 뒤 클래스 선언으로 이동해 디컴파일된 메서드를 연다. 동일 이름의 클래스가 여러 의존성에 있으면 대상 배포가 사용하는 JAR를 먼저 식별한다.
• 상태는 확정, 미확정, 해당 없음으로 기록한다. 미확정에는 부족한 파일·설정·코드 또는 결정할 수 없는 순서를 적는다. 해당 없음은 적용 여부를 판정한 뒤 사용한다.
• melcostorefront, melcocore, regionalcore, 아래 경로와 순번은 예시다. 실제 프로젝트 결과는 미기입 상태다.

1. 대상 배포와 테넌트 ID

입력 및 목적. 분석할 PDP URL 또는 이미 파악한 Controller, 해당 앱을 실행하는 로컬 설치를 입력으로 삼는다. 이 단계의 결과는 설치 경로 + 설정 경로 + 버전 + 웹 앱 + 배포 컨텍스트 경로 + 테넌트 ID다.

1.1. PDP를 처리하는 웹 extension을 식별한다

1. IntelliJ에서 알고 있는 PDP Controller를 연다. 클래스와 메서드의 @RequestMapping 또는 @GetMapping을 확인하여, 상품 상세 요청에 대응하는 메서드 시그니처를 기록한다. 클래스 이름을 모르면 PDP URL의 변하지 않는 경로 부분과 상품 코드 변수명으로 검색하고, 클래스·메서드 수준 매핑을 함께 읽는다. URL 재작성이나 프록시가 있다면 외부 URL과 백엔드 요청 경로를 구별한다.
2. Controller 파일의 상위 디렉터리를 올라가 가장 가까운 extensioninfo.xml을 찾고, <extension name="…">을 기록한다. <webmodule webroot="…">이 있는 경우 선언된 웹 모듈 경로도 기록한다.
3. 같은 extension의 web/webroot/WEB-INF/web.xml에서 <servlet>, <servlet-mapping>, contextConfigLocation을 확인한다. contextConfigLocation은 웹 앱 전체의 <context-param>과 서블릿 내부의 <init-param>을 각각 확인한다. 설정에 적힌 XML을 열고, Controller의 패키지가 <context:component-scan base-package="…">에 포함되는지 또는 해당 클래스가 <bean>이나 Java 설정으로 등록되는지 확인한다. 필요한 경우 설정의 <import>도 따라간다.
4. Controller 소속 extension에 <webmodule>이 없거나 여러 웹 앱이 같은 Controller를 사용하는 경우, 대상 배포의 웹 모듈 후보별로 3번을 반복한다. 대상 URL을 받는 매핑과 Controller 등록을 모두 설명할 수 있는 앱을 선택한다.
5. CMSSite의 사이트 ID나 urlPatterns를 확보했다면 사이트 구분 근거로 기록한다. 도메인이나 사이트 ID가 여러 개여도 동일한 웹 모듈과 배포 매핑을 공유하는 경우가 있다.

완료 기준. PDP 요청 경로 → 서블릿 매핑 → Controller 등록 → 웹 extension 이름의 연결 근거가 있다. 후보가 남으면 후보마다 부족한 자료를 적고 웹 앱 판정을 미확정으로 둔다. SAP 문서 [1], [2], [3].

1.2. 실제 배포 컨텍스트 경로와 설치를 식별한다

1. 대상 로컬 서버의 실행 구성 또는 프로젝트의 시작 스크립트를 열어 사용하는 설치 경로와 설정 디렉터리를 확인한다. IntelliJ에서 관리되는 실행 구성이면 프로젝트에 공유된 .run 또는 .idea/runConfigurations가 있는지 확인한다. 설정 디렉터리가 별도로 지정되어 있으면 실제 위치를 CONFIG_DIR로 기록한다.
2. Tomcat을 사용하는 경우 대상 실행 구성이 선택하는 Tomcat 인스턴스나 catalina.base를 확인한다. 그 인스턴스의 conf/server.xml을 열고 대상 Host 아래에 웹 앱의 <Context>가 있는지 확인한다. 개별 Context 파일을 쓰는 구성이라면 해당 인스턴스의 conf/<Engine>/<Host>/ 아래에서 Context XML을 찾는다. 기본 이름을 사용하는 환경의 탐색 출발점은 conf/Catalina/localhost/이다. SAP의 생성 방식은 적용된 tomcat.legacy.deployment 및 생성된 설정 파일을 함께 확인한다.
3. 대상 Context의 docBase 또는 해당 설정이 지정하는 배포 위치를 1.1에서 확인한 extension의 산출물과 대조한다. server.xml에서 컨텍스트 경로를 명시한 구성이라면 해당 값을 기록한다. 호스트의 자동 배포가 개별 Context 파일명으로 경로를 정하는 Tomcat 구성이라면, 예를 들어 shop.xml은 /shop, ROOT.xml은 루트, shop#jp.xml은 /shop/jp다. 배포 버전 접미사가 있다면 해당 Tomcat 버전의 Naming 규칙을 함께 확인한다. 외부 프록시 설정이 있으면 외부 URL의 접두사가 백엔드에서 제거되거나 변경되는지 확인한다.
4. 대상 설치의 bin/platform/build.number에서 build.version 전체 값을 기록한다. 2211 계열 표시만 있거나 파일이 없으면 동일 설치의 빌드 자료에서 정확한 패치를 보완한다.

판정 예시. 소스의 선언이 /melcostorefront이고 대상 Tomcat Context가 그 extension의 산출물을 가리키며 최종 컨텍스트 경로가 /shop이라면, 선언값은 /melcostorefront, 1.3에 사용할 실제 앱 경로는 /shop으로 기록한다.

완료 기준. 대상 앱의 배포 설정 위치, 배포 디렉터리 또는 산출물, 실제 컨텍스트 경로, 설치·설정 경로, 정확한 버전이 한 배포를 가리킨다. 생성된 배포 자료를 확보하지 못하면 소스의 선언값만 기록하고 배포 경로는 별도로 판정한다. SAP 문서 [4], [5], Tomcat 문서 [19].

1.3. 웹 컨텍스트 리스너에서 테넌트 선택 경로를 확인한다

1. 실제 배포 위치의 WEB-INF/web.xml에서 <listener><listener-class>…</listener-class></listener>를 찾는다. 웹 컨텍스트 리스너는 웹 앱 초기화 시 서블릿 컨테이너가 호출하는 클래스다. 여러 리스너가 있다면 클래스 선언과 상속 구조를 열어 Spring 웹 루트와 그 부모를 구성하는 리스너를 식별한다. 배포본과 소스 파일이 다르면 배포본을 근거로 사용한다.
2. 등록된 클래스를 열고 contextInitialized, initWebApplicationContext, loadParentContext(ServletContext), adjustTenant 또는 getTenanId(String) 중 실제 호출되는 메서드를 따라간다. 이 메서드 이름은 SAP 2211 API에서 찾을 수 있는 탐색 지점이다. 상속한 클래스에 구현이 없으면 부모로 이동하고, 재정의가 있으면 호출 흐름에 사용되는 구현을 확인한다. 메서드가 존재하더라도 실제로 호출되는지는 본문에서 확인한다.
3. 코드가 테넌트 ID를 결정하는 분기를 기록한다. 경로 기반 구현이 Utilities.getTenantIdForContext(servletContextPath, defaultTenant)를 호출한다면 첫 번째 인수의 실제 출처, 두 번째 인수의 값, 그 메서드가 조회하는 매핑, 매핑이 없을 때 처리를 메서드 본문에서 읽는다. 웹 앱에 ID를 부착하는 코드가 보이면 attachTenantIDToServletContext 호출이나 관련 속성을 확인한다. 웹 앱의 부착 값과 부모 코어 선택이 각각 어떤 값으로 결정되는지도 대조한다.
4. CONFIG_DIR/local.properties와 HYBRIS_HOME/bin/platform/project.properties에서 installed.tenants를 찾는다. 대상 실행이 적용하는 다른 설정이 있으면 함께 읽는다. 키를 한 파일에서 찾지 못한 경우 플랫폼 기본값 또는 다음 설정층을 확인한다. 이 값은 추가 테넌트 후보를 확인하는 데 쓴다.
5. 경로로 테넌트를 선택하는 구현이라면 위에서 찾은 각 후보 ID에 대해 HYBRIS_HOME/bin/platform/tenant_<ID>.properties와 CONFIG_DIR/local_tenant_<ID>.properties를 연다. 현재 웹 extension의 <extension>.webroot 또는 코드가 실제로 읽는 키를 찾아 최종값을 정한다. 그 값을 1.2에서 기록한 실제 컨텍스트 경로와 코드의 동일한 정규화·비교 규칙으로 대조한다.
6. 커스텀 구현이 경로 대신 서블릿 속성, 프로퍼티 또는 고정 ID를 사용하면 그 분기를 적용한다. 속성이 설정되기 전 읽힐 수 있거나 배포 외부 값에 의존한다면, 값을 넣는 코드 또는 배포 설정까지 확인한 뒤 판정한다.

|확인 결과                               |조치                                                                                |
|------------------------------------|----------------------------------------------------------------------------------|
|특정 매핑이나 커스텀 분기가 ID 한 개를 반환함         |해당 ID와 선택된 분기를 기록한다.                                                              |
|추가 매핑이 없고 실제 기본 분기가 `master`를 선택함   |`master`와 기본 분기의 근거를 기록한다.                                                        |
|둘 이상의 매핑 후보 또는 외부 값 때문에 ID를 확정할 수 없음|후보 ID, 충돌한 설정, 다음에 확인할 메서드나 배포 파일을 기록한다.                                          |
|실행 스레드에서 테넌트를 별도로 바꾸는 코드가 조사 목적에 관련됨|웹 루트의 부모 코어를 고르는 값과 요청 스레드에서 사용하는 값의 결정 지점을 각각 확인한다. PDP 분석 기준이 달라지면 두 값을 따로 기록한다.|

완료 기준. 배포 경로 또는 선택 입력 → 등록 리스너/실제 동작 → 테넌트 ID → 선택된 코어 컨텍스트가 연결된다. installed.tenants 값 또는 사이트 ID만 확보한 상태라면 현재 앱과 테넌트 연결은 아직 판정할 수 없다. SAP 문서 [6], [7], [8], [9].

2. 배포에 포함된 extension 목록

입력 및 목적. 1.2의 설치 경로와 설정 디렉터리를 기준으로 대상 배포에 포함된 extension을 이름과 물리 경로로 식별한다.

1. CONFIG_DIR/localextensions.xml을 연다. 각 <extension>의 name 또는 dir과 각 <path>의 dir, autoload, depth를 기록한다. 대상 설정에 localextensions.xml이 없으면 실제 빌드가 대체로 읽는 extension 설정을 빌드 코드나 빌드 자료에서 확인한다.
2. dir로 직접 지정된 extension의 폴더에서 extensioninfo.xml을 열고 <extension name="…">을 읽는다. name으로 지정되어 있다면, 빌드가 참조하는 탐색 경로에서 해당 이름의 extensioninfo.xml을 찾는다. HYBRIS_BIN_DIR 같은 경로 변수는 대상 빌드의 값으로 치환한다.
3. autoload 경로와 플랫폼 핵심 extension 자동 로드 규칙을 적용한다. bin/platform/ext는 확인할 출발점이다. 프로젝트의 실제 빌드 설정이 사용하는 경로·깊이·활성화 규칙을 읽고, 해당 규칙으로 추가된 extension마다 출처를 남긴다.
4. 각 extensioninfo.xml의 <requires-extension name="…">을 읽는다. 아직 목록에 없는 의존 extension을 추가하고, 그 extension의 파일도 연다. 새 의존성이 없을 때까지 반복한다. 의존성을 기록할 때는 선행 extension → 의존하는 extension 방향으로 저장한다.
5. 동일 이름이 여러 폴더에서 발견되면 대상 빌드가 어떤 경로를 선택하는지 확인한다. 이미 대상 배포의 최종 extension 목록이 있으면 이름 집합과 선택된 경로를 대조하고, 차이가 나는 항목은 입력 파일·빌드 구성이 같은지 확인한다.

|상황                          |조치                                                          |
|----------------------------|------------------------------------------------------------|
|명시 확장·자동 로드·의존성을 모두 경로까지 확인함|extension별로 포함 이유와 `extensioninfo.xml` 경로를 기록한다.            |
|같은 이름의 후보가 여러 개임            |빌드의 선택 규칙과 순서를 확인하거나 후보 경로를 모두 남긴다.                         |
|필요한 의존성 파일을 찾을 수 없음         |어떤 extension이 그 이름을 요구하는지 기록하고, 대상 배포 산출물 또는 누락된 탐색 경로를 찾는다.|

완료 기준. 최종 배포 extension마다 이름, 실제 경로, 포함 근거가 있다. 의존 edge가 모두 해석되었고 중복 이름에 대한 선택 근거가 있다. SAP 문서 [1], [10], [11].

3. 대상 테넌트에 활성화된 extension 목록

입력 및 목적. 1장의 테넌트 ID와 2장의 배포 extension 집합을 사용해, 코어 로더가 해당 테넌트에 대해 실제로 검토하는 extension 집합을 확정한다.

1. 대상 2211 패치의 de.hybris.platform.core.Registry를 열어 getCoreApplicationContext()부터 코어 컨텍스트를 얻는 경로를 읽는다. 해당 메서드가 이미 구성된 컨텍스트를 반환하면, 아래 2번으로 실제 구성 코드를 찾는다.
2. 대상 설치의 플랫폼 소스에서 Loading Spring configuration for와 .application-context를 검색한다. 소스에 없으면 해당 배포의 플랫폼 JAR에서 Registry와 AbstractTenant의 컨텍스트 구성 코드로 들어가 호출 클래스를 확인한다. 로그 문자열이 변경됐을 경우 테넌트 코어 컨텍스트용 리소스 목록을 만드는 메서드와 그 호출자를 찾아 역추적한다.
3. 코어 로더가 extension 이름 또는 객체를 얻는 변수의 선언으로 이동한다. 호출 경로에 Tenant.getTenantSpecificExtensionNames()가 있으면 대상 테넌트의 구체 구현인 MasterTenant 또는 해당 테넌트 구현을 열어 반환값의 출처를 확인한다. Utilities.getExtensionNames(), getInstalledExtensionNames(Tenant) 또는 PlatformConfig를 사용하는 코드가 있다면 어떤 결과를 로더가 실제로 사용하고 어떤 필터를 추가로 적용하는지를 확인한다.
4. 코드가 테넌트별 설정을 읽는 경우, 읽는 정확한 키를 메서드 본문에서 찾고 대상 tenant_<ID>.properties, local_tenant_<ID>.properties 또는 실제 설정 소스의 최종값을 대조한다. 빈 값, 키가 없는 경우, 명시 목록의 처리 분기를 각각 확인한다.
5. 2장의 각 extension에 대해 이 테넌트에서 활성, 대상 제외 또는 미확정을 표시하고, 로더의 판정 지점이나 배포 자료를 연결한다. 활성 여부와 진입 XML 존재 여부는 각기 기록한다.

예시. 설치에 regionalcore가 포함되어 있어도, 로더가 사용하는 대상 테넌트의 목록에서 제외되는 것이 코드와 설정으로 확인되면 대상 제외다. 목록에는 있지만 코어 XML이 있는지 아직 확인하지 않았다면 대상 활성 / 진입 XML 조사 전으로 기록한다.

완료 기준. 테넌트별 선별 규칙과 배포 extension의 적용 결과를 재현할 수 있다. 로더의 목록 출처를 확인하지 못한 경우, 시스템 전체 목록만 가지고 테넌트별 최종 목록을 확정하지 않는다. SAP 문서 [7], [9], [12], [13].

4. extension별 코어 Spring 설정의 최종값

입력 및 목적. 3장의 활성 extension마다 코어 진입 XML을 선택하는 키와 값을 구한다.

1. 대상 extension 이름이 melcocore라면 melcocore.application-context를 정확한 문자열로 검색한다. 우선 extension 루트의 project.properties, HYBRIS_HOME/bin/platform/project.properties, CONFIG_DIR/local.properties를 확인한다. 다른 활성 extension의 프로퍼티와 배포 오버라이드에도 같은 키가 있는지 검색한다.
2. 테넌트별 설정을 읽는 구현이라면 3장에서 확인한 테넌트 설정층도 포함한다. 시스템 속성이나 별도 설정 파일이 영향을 주는지는 3장에서 찾은 로더와 설정 접근 코드에서 확인한다. 예를 들어 getParameter 또는 getString에 전달하는 실제 키, 기본값 인수, 사용한 설정 객체를 기록한다.
3. 키가 여러 번 정의되면 각 원문, 파일과 줄, 덮어쓰는 층을 기록한다. 같은 우선순위의 후보가 경쟁한다면 로더가 사용하는 프로퍼티 합성 순서까지 확인한다. 값이 존재하지 않는 경우와 빈 문자열인 경우를 구별해 5장으로 넘긴다.
4. <extension>.global-application-context와 Storefront 웹 앱의 contextConfigLocation은 각각 다른 컨텍스트 자료로 표기한다. 위 키나 웹 설정을 코어 진입 XML의 출처로 분류하기 전에는 실제 대상 코어 로더가 그것을 소비하는지 확인한다.

|예시 입력                                                                       |기록                                               |
|----------------------------------------------------------------------------|-------------------------------------------------|
|extension `project.properties`에 A, 적용 `local.properties`에 B가 있고 적용 규칙상 B가 우선|`최종값 B, 출처 local.properties:<행>, 이전 후보 A`        |
|설정 키가 어느 적용층에도 없음                                                           |`미설정`으로 기록하고 기본 파일명 분기를 확인                       |
|설정 키가 있고 값이 공백 또는 빈 문자열임                                                    |원문을 기록하고 로더가 trim·기본 파일 탐색·건너뛰기 중 무엇을 하는지 확인     |
|프로퍼티 값에 배포 변수 또는 경로 변수가 포함됨                                                 |치환을 수행하는 시점과 값의 출처를 확인하고, 자료가 없으면 해당 경로를 미확정으로 기록|

완료 기준. 활성 extension 각각에 최종값과 출처, 또는 미설정이 입증되어 있다. 동순위 경쟁이나 값 미확정에 영향을 받는 extension이 식별되어 있다. SAP 문서 [7], [14], [15].

5. 코어 로더의 기본값과 리소스 선택 규칙

입력 및 목적. 3장에서 찾은 동일한 코어 XML 선택 메서드와 4장의 최종값을 대조해, 어떤 문자열이 어떤 분기에서 XML 후보가 되는지 확인한다.

1. .application-context 키를 읽는 줄에서 시작해 null, 빈 값·공백, 값이 있는 경우의 조건문을 찾는다. 각 분기가 어떤 파일명 문자열을 만드는지 기록한다. SAP 문서에 나오는 기본 후보는 <extension>-application-context.xml이다. <extension>-spring.xml은 일반적인 설정 파일명이므로 실제 설정값이나 구현에 사용 근거가 있는지 확인한다.
2. 값에 쉼표가 있는 경우 파일 리스트를 만드는 메서드를 연다. 토큰의 순서 유지 여부, 공백과 빈 토큰의 처리, 중복 이름을 어떻게 처리하는지 기록한다.
3. 각 후보를 검사하는 메서드를 열어 클래스 로더 객체와 호출 메서드, 파일시스템으로 넘어가는 조건, 리소스를 찾지 못했을 때 행동을 읽는다. SAP 문서는 기본적인 클래스패스 우선과 파일시스템 탐색을 설명한다. 설치 경로 기준이 필요한 경우 코드가 상대 경로를 어느 디렉터리에 연결하는지 확인한다.
4. 코드가 추가 리소스 경로를 합치거나 사용자 지정 설정을 끼워 넣는다면 그 경로와 위치도 표에 추가한다. 결과 파일을 반환하는 지점까지 추적해 로그를 쓰는 줄과 실제 Spring에 넘기는 줄을 비교한다.

완료 기준. 대상 버전의 코드로 미설정, 빈 값, 단일 파일, 복수 파일, 리소스 미발견에 해당하는 분기를 설명할 수 있다. 구현을 보지 못한 분기는 문서가 말하는 규칙과 따로 기록하여 경계 동작을 임의로 확정하지 않는다. SAP 문서 [14].

6. 진입 XML의 실제 파일 또는 JAR 엔트리

입력 및 목적. 5장의 각 후보 문자열을 대상 배포에서 실제 선택될 리소스로 바꾼다.

1. 5장에서 기록한 클래스 로더와 그 검색 대상을 만드는 코드를 확인한다. 로더가 사용하는 클래스패스 디렉터리, JAR, 부모 위임 또는 자체 로더 정책을 파악할 수 있는 시작 스크립트, 컨테이너 설정, 배포 산출물 목록을 대조한다. IntelliJ의 분석용 클래스패스와 실제 배포의 클래스패스는 각각 확인한다.
2. 후보가 melcocore-spring.xml이면 <extension>/resources/부터 소스 원본을 찾는다. 그 뒤 실제 대상 클래스패스의 디렉터리나 JAR에서 로더가 요구한 정확한 상대 경로의 파일 또는 엔트리를 찾는다. 소스 파일을 발견했다면 포함된 배포 경로까지 연결한다.
3. JAR를 파일 탐색기에서 열기 어려우면 IntelliJ의 Libraries에서 열거나 JDK의 jar tf "<대상 JAR의 절대 경로>"를 실행할 수 있다. 이 명령의 목적은 JAR 엔트리 목록을 읽어 후보 XML이 어떤 상대 경로에 들어 있는지 확인하는 것이다. 출력에서 후보 XML의 전체 엔트리 경로를 찾는다. 여러 JAR를 확인할 때는 JAR 경로와 엔트리 경로를 같이 기록한다.
4. 동명 후보가 여러 개면 로더의 위임 및 검색 순서에 맞춰 선택되는 하나를 판정한다. 대상 탐색이 여러 리소스를 반환하는 방식이면 실제 순회 가능한 후보 전체와 순서 근거를 남긴다. 클래스패스 탐색에 실패하고 파일시스템 분기로 넘어가는 경우, 해당 분기가 계산하는 위치에서 파일을 확인한다.
5. 후보가 없어도 로더가 계속 진행하는 분기라면 로드 대상 없음을 기록한다. 시작이 중단되는 동작이라면 누락으로 실패를 표시한다. 실제 탐색 대상 전체를 확인하지 못했다면 미확정으로 기록한다.

기록 예시. melcocore-spring.xml → C:/deploy/lib/melcocore.jar!/melcocore-spring.xml. 앞부분은 물리 JAR 위치이고 !/ 뒤는 그 안의 엔트리다. 실제 파일시스템 리소스는 절대 경로로 기록한다.

완료 기준. 모든 활성 extension에 대해 진입 XML 각각의 물리적 식별자가 있거나, 진입 XML 없음·실패·미확정의 근거가 있다. 같은 이름의 다른 파일을 임의로 선택한 행이 없다.

7. extension 사이의 로드 순서

입력 및 목적. 2~3장의 extension 집합과 의존성을 바탕으로, 코어 로더가 각 extension을 어떤 순서대로 처리하는지 확인한다.

1. 3장에서 찾은 코어 구성 코드에서 extension을 순회하는 반복문 또는 스트림을 연다. 순회 대상 변수를 초기화하는 지점으로 이동해 리스트 생성, 필터, 정렬, 삽입, 병합 순서를 기록한다.
2. 빌드 순서 자료를 이용하는 경로라면 de.hybris.bootstrap.config.PlatformConfig.getExtensionInfosInBuildOrder() 등의 호출이 실제 경로에 있는지 확인한다. 메서드가 목록을 반환한다는 사실에 이어, 대상 로더가 반환 순서를 유지하는지를 호출자까지 포함해 읽는다.
3. 대상 빌드에 순서가 보존된 산출물이 있으면 어떤 도구나 코드가 생성했고 순서의 의미가 무엇인지 확인한다. 로더가 다른 기준으로 재정렬한다면 그 정렬까지 적용한다. 이 산출물이 없으면 대상 패치의 자료구조 및 순서 산출 코드를 따라가고, 입력 순서·동률 처리·디렉터리 열거 순서가 결과를 바꾸는지 확인한다.
4. <requires-extension>에서 도출한 선후관계가 최종 순서에서 성립하는지 검사한다. 독립 extension 사이의 순서까지 근거가 있는 경우에만 절대 순번을 부여한다. 근거가 부분적이면 A < B와 같이 확인된 관계를 기록하고, 미확정 쌍의 후보를 남긴다.

|사례                                                 |판정                                                                              |
|---------------------------------------------------|--------------------------------------------------------------------------------|
|`melcocore`가 `basecatalog`를 요구함                    |해당 규칙이 적용되면 `basecatalog < melcocore`로 기록                                       |
|`melcocore`와 `regionalcore`가 둘 다 `basecatalog`를 요구함|의존성으로는 `basecatalog`가 둘보다 앞선다는 사실을 확정. 두 커스텀 extension의 상대 순서는 로더 입력과 동률 처리에서 확인|
|관련 순서가 보존된 대상 빌드 자료와 로더 적용 방식이 모두 확인됨              |활성 대상으로 남은 extension에 대해 순번 부여                                                  |
|동일 alias에 대한 승자가 두 가능한 순서에서 달라짐                    |영향받는 alias와 순서 조건을 기록. XML 목록 확정과 순서 미확정을 별도로 보고                                |

완료 기준. 실제 순회 순번과 근거가 전부 확보되어 있으면 이 단계는 확정이다. 의존성만으로 일부 순서가 확보된 경우에는 순서의 확정 범위와 추가로 필요한 자료를 기록한다. SAP 문서 [11], [12], [14].

8. 동일 extension 안의 진입 XML 순서

입력 및 목적. 4장의 원문과 5장의 로더 분기, 6장의 실제 리소스를 연결해 extension 내부 순서를 확인한다.

1. 프로퍼티를 분해하는 메서드에 돌아가 설정값의 왼쪽에서 오른쪽 순서가 결과 자료구조에 어떤 순서로 삽입되는지 읽는다.
2. 삽입된 리소스가 Spring XML 로더로 전달될 때 역순, 정렬, 중복 제거, 추가 삽입이 있는지 호출자까지 따라간다. List를 순회한다고 확인한 뒤에도 목록을 구성하는 앞 단계에서 순서가 바뀌는지 확인한다.
3. 실제 리소스마다 extension 내부 순번, 설정 토큰의 위치, 선택된 물리 경로를 기록한다. 토큰이 건너뛰어지거나 오류가 발생하면 해당 분기와 뒤쪽 토큰의 처리 여부를 적는다.

예시. 최종값이 melcocore-spring.xml,melcocore-overrides.xml이고 코드가 그 순서로 전달한다면, 같은 extension의 진입 순번 1과 2를 각각 부여한다. 첫 파일의 <import>는 9장의 이벤트 표에서 처리 위치를 표시한다.

완료 기준. 각 extension에서 실제 로드되는 진입 XML의 상대 순서가 코드로 확인되어 있다. 7장이 미확정이어도 이 단계에서 extension 내부 순서는 독립적으로 확정할 수 있다. SAP 문서 [14].

9. XML 내부 import 처리 순서

입력 및 목적. 각 진입 XML과 그 하위 import를 열어, Spring XML 로더가 같은 코어 레지스트리에서 처리하는 실제 리소스와 위치를 구한다.

1. 대상 배포에서 Spring Beans 및 Core JAR의 버전을 확인한다. 라이브러리 이름만으로 부족하면 해당 JAR의 META-INF/MANIFEST.MF 또는 빌드의 의존성 자료를 확인한다. 대상 Spring 버전에서 org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader의 doRegisterBeanDefinitions, parseBeanDefinitions, importBeanDefinitionResource를 연다. 로더가 문서 리더를 교체하는 코드가 있으면 실제 구현을 사용한다.
2. 진입 XML의 최상위 <beans>부터 문서 순서대로 읽는다. profile이 있는 <beans>는 그 분기의 활성 여부를 먼저 판정한다. 분석 대상 설정에서 활성 profile과 그 값의 출처를 기록한다. profile이 미확정이면 그 블록에서만 생기는 import를 조건부로 남긴다.
3. <import resource="…">를 만나면 속성 원문, 선언 파일과 줄을 기록한다. ${…}가 있으면 문서 리더가 읽는 환경 설정과 치환 코드를 확인한 후 값을 넣는다. 속성 해석에 실패하면 대상 리더가 보고하는 오류 또는 계속 진행하는 분기를 확인한다.
4. 상대 경로는 현재 XML의 리소스를 기준으로 해석하는 코드와 대조한다. classpath:, file:, classpath*:, 와일드카드가 있다면 대상 XmlBeanDefinitionReader와 리소스 로더를 확인한다. 패턴을 처리하는 경로에서 PathMatchingResourcePatternResolver.getResources가 사용될 경우 매칭 대상과 열거 결과의 순서를 확인한다. 파일명순은 코드 또는 입력으로 확인한 경우에 적용한다.
5. 선택된 하위 XML에 2~4번을 재귀적으로 적용한다. 해당 파일의 처리가 끝나면 부모 XML의 다음 요소로 돌아간다. 동일 리소스가 여러 곳에서 참조되면 각 import 발생 지점을 보존하고, 실제 리더가 읽는 횟수는 호출 흐름에 따라 판정한다. 순환이 있으면 현재 처리 경로와 오류 발생 지점을 기록한다.
6. 사용자 정의 XML 네임스페이스나 Java 코드가 별도로 XML을 로드할 가능성이 보이면 그 진입 지점을 기록한다. 이 절차의 전체 로드 주장에 해당 경로까지 포함하려면 실제 핸들러나 등록 코드를 확인한다. <import> 체인까지의 완료 여부와 프로그램 방식의 추가 로드 여부는 각각 표시한다.

|처리 위치 예시|이벤트                            |근거                                         |
|--------|-------------------------------|-------------------------------------------|
|1       |`melcocore-spring.xml` 진입      |해석된 리소스와 extension 내부 1순번                  |
|2       |진입 XML 앞부분 처리                  |예시 `melcocore-spring.xml:12`               |
|3       |`spring/product-dao.xml` import|예시 `melcocore-spring.xml:25`의 `resource` 속성|
|4       |진입 XML의 import 다음 요소 처리        |예시 `melcocore-spring.xml:31`               |
|5       |`melcocore-overrides.xml` 진입   |해석된 리소스와 extension 내부 2순번                  |

완료 기준. 모든 선언적 import에 대해 활성 여부, 대상 리소스, 호출 파일·줄, 부모 파일로 돌아오는 위치를 설명할 수 있다. wildcard의 매칭 결과나 순서가 미확정이면 해당 import의 영향 범위를 기록한다. Spring 문서 [16], [17], [18].

10. 최종 산출물과 완료 판정

아래 표를 한 세트로 보관한다. 같은 extension에서 여러 XML을 읽으면 파일마다 행을 만들고, 같은 파일이 여러 import 지점에서 읽히면 발생 지점마다 이벤트를 남긴다.

10.1. 대상 기록

|항목      |기록값                                        |
|--------|-------------------------------------------|
|대상 설치·버전|`HYBRIS_HOME`, `build.version`, 근거 파일      |
|대상 설정   |`CONFIG_DIR`, 적용한 배포별 설정                   |
|웹 앱     |extension 이름, Controller, 실제 배포 Context와 근거|
|테넌트     |ID와 선택 분기, 근거 파일 또는 클래스·메서드                |
|클래스패스   |대상 코어 로더 기준 자료의 경로 또는 미확정 근거               |

10.2. extension 및 진입 XML 목록

|테넌트   |extension  |포함·활성 근거      |최종 설정값과 출처                                    |진입 XML 실제 위치                       |순서             |상태|
|------|-----------|--------------|----------------------------------------------|-----------------------------------|---------------|--|
|master|basecatalog|예시 의존성 및 활성 목록|`basecatalog-spring.xml`                      |`<배포 JAR>!/basecatalog-spring.xml` |`melcocore`보다 앞|예시|
|master|melcocore  |예시 테넌트 활성 목록  |`melcocore-spring.xml,melcocore-overrides.xml`|`<배포 JAR>!/melcocore-spring.xml`   |extension 내부 1 |예시|
|master|melcocore  |위와 같음         |위와 같음                                         |`<배포 JAR>!/melcocore-overrides.xml`|extension 내부 2 |예시|

10.3. import 처리와 미확정 항목

|영역     |필요한 값                               |근거 및 미확정 시 다음 확인 위치                |
|-------|------------------------------------|-----------------------------------|
|import |부모 리소스, 요소 위치, 원문, 치환값, 실제 대상, 처리 순번|부모 XML 줄과 배포 설정·리소스 위치를 기록         |
|미확정 순서 |영향받는 extension·XML 쌍, 확인된 선후관계      |로더 반복문의 목록 생성 코드 또는 배포에 보존된 순서 자료  |
|미확정 리소스|후보 경로·JAR, 선택에 부족한 값                |클래스패스 생성 코드, 외부 설정 또는 profile 적용 자료|

10.4. 최종 체크

☐ 체크 1 대상 배포, 정확한 패치, 대상 테넌트 ID를 증거와 함께 확인했다.
☐ 체크 2 배포에 포함된 extension의 이름과 실제 경로를 확인했다.
☐ 체크 3 대상 테넌트에서 활성화되는 extension의 범위를 확인했다.
☐ 체크 4~6 extension별 최종 설정값과 선택 분기를 적용해 모든 진입 XML의 실제 리소스를 확인했다.
☐ 체크 7~8 extension 간 순서와 각 extension 내부 XML 순서를 확인했다.
☐ 체크 9 활성 import를 선언 위치에서 처리하고 중첩 리소스와 처리 순서를 확인했다.

결과 판정. 체크 16을 충족하면 extension별 진입 XML 목록 확정으로 보고한다. 체크 79까지 충족하면 선언적 import를 포함한 코어 XML 처리 순서 확정으로 보고한다. 일부 순서만 입증된 경우에는 확인한 관계와 미확정 관계를 함께 제공한다. 최종 빈 목록 분석으로 넘어갈 때는 이 순서 기록을 입력으로 삼고, XML 이후 Java 등록 로직은 별도 조사한다.

10.5. 한 배포에 적용하는 예시

이 예시에서는 설명에 필요한 파일만 표시한다. 파일명, 설정값, 로더 분기와 산출물은 가상의 구성이다.

|단계             |열어서 확인한 예시 자료                                                                                                                        |판정 결과                                                                                         |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
|대상 앱 식별        |`melcostorefront/extensioninfo.xml`의 `webroot=/melcostorefront`, 대상 Tomcat의 `conf/Catalina/localhost/shop.xml`의 `docBase`            |`docBase`가 `melcostorefront` 산출물을 가리킨다. 이 예의 자동 배포 컨텍스트는 `/shop`이다.                           |
|테넌트 판정         |해당 배포본의 `WEB-INF/web.xml`에 등록된 리스너, 그 리스너의 경로 매핑 호출, 최종 `installed.tenants=junit`, `junit`에 적용된 `melcostorefront.webroot=/shop-junit`|비교 인수는 `/shop`이다. `/shop-junit`과 일치하지 않고, 해당 구현의 기본 인수가 `master`임을 확인했으므로 `master`로 기록한다.     |
|활성 extension 판정|`localextensions.xml`과 각 `extensioninfo.xml`, 코어 로더가 사용하는 대상 테넌트 목록의 생성 분기                                                           |예시에서 `basecatalog`, `melcocore`, `regionalcore`가 포함되고 활성화된다.                                  |
|리소스 판정         |`CONFIG_DIR/local.properties`의 `melcocore.application-context=melcocore-spring.xml,melcocore-overrides.xml`, 대상 JAR의 두 엔트리           |`melcocore` 진입 XML 두 개의 실제 리소스를 기록한다.                                                         |
|import 판정      |`melcocore-spring.xml`의 `<import resource="spring/product-dao.xml"/>`와 같은 JAR의 `spring/product-dao.xml`                              |기본 XML을 읽다가 해당 줄에서 하위 XML을 처리하고 돌아온 뒤 나머지 요소를 처리한다. 뒤이어 `melcocore-overrides.xml`을 읽는다.       |
|전체 순서 판정       |`melcocore`와 `regionalcore`가 둘 다 `basecatalog`를 요구하는 선언, 확인한 배포 자료                                                                   |`basecatalog` 선행만 입증됐다면 전체 순서는 미확정이다. 로더가 실제로 순회하는 리스트를 만드는 코드나 순서가 보존된 빌드 자료를 다음 확인 위치로 기록한다.|

11. 참고 문서

아래 번호는 본문에서 확인 위치를 뒷받침하는 문서 번호다. 프로젝트별 분기와 순서는 대상 설치의 실제 파일·코드로 확인한다.

[1] SAP Help — Extension Configurations
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8bbf6b5e86691014ad49fd1bc0172e5c.html

[2] SAP Help — Extension Components
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/3a3b92d4900b4b3685157b806a73eab2.html

[3] SAP Help — Just One Storefront
https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/7e47d40a176d48ba914b50957d003804/8ae0711f86691014877ae05249b2f5ac.html

[4] SAP Help — Configuring SAP Commerce Server, Tomcat Context 배포 설정
https://help.sap.com/docs/SAP_COMMERCE/a74589c3a81a4a95bf51d87258c0ab15/063e4dc0cac14b2e8656f751caccd448.html

[5] SAP Help — Finding Your SAP Commerce Version
https://help.sap.com/docs/SAP_COMMERCE/5671512a37c74e1daa2d8191b163dc61/aa595ad5ed9d4f138bafe35adc01e6d8.html

[6] SAP Help — Multitenancy
https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/8c14e7ae866910148e59ebf4a2685857.html

[7] SAP Commerce 2211 API — Registry
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/core/Registry.html

[8] SAP Commerce 2211 API — HybrisContextLoaderListener
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/spring/HybrisContextLoaderListener.html

[9] SAP Commerce 2211 API — Utilities
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/util/Utilities.html

[10] SAP Help — Configuration Options in localextensions.xml
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/cce26d8ef435425fb9e054d91794148c.html

[11] SAP Help — Building SAP Commerce
https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/8b6ded0d86691014a6fab18e171c1f91.html

[12] SAP Commerce 2211 API — PlatformConfig
https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2211/en-US/de/hybris/bootstrap/config/PlatformConfig.html

[13] SAP Commerce 2211 API — Tenant
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/core/Tenant.html

[14] SAP Help — Spring Framework in SAP Commerce
https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/8c63621986691014a7e0a18695d7d410.html

[15] SAP Help — Where to Define Property Values
https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8b8e13c9866910149d40b151a9196543.html

[16] Spring Framework 6.2 API — DefaultBeanDefinitionDocumentReader
https://www.springframework.org/spring/docs/6.2.7/javadoc-api/org/springframework/beans/factory/xml/DefaultBeanDefinitionDocumentReader.html

[17] Spring Framework 6.2 API — XmlBeanDefinitionReader
https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/beans/factory/xml/XmlBeanDefinitionReader.html

[18] Spring Framework 6.2 API — PathMatchingResourcePatternResolver
https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/io/support/PathMatchingResourcePatternResolver.html

[19] Apache Tomcat 9 Configuration Reference — The Context Container, Naming
https://tomcat.apache.org/tomcat-9.0-doc/config/context.html