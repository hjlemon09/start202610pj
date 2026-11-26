> SAP Commerce 2211 지정 테넌트의 코어 Spring XML 조사 절차서 — 3/13장. 이전: [2-2. extension 사이의 로드 순서 확인](02_2-extension-사이의-로드-순서-확인.md) · 다음: [4. extension별 코어 설정값과 출처 확인](04-extension별-코어-설정값과-출처-확인.md)

# 3. 지정 테넌트의 활성 extension 집합 확인

## 2-2장에서 확정한 내용

## 3장의 내용

작업 목적. 대상 배포의 extension 중 지정 테넌트의 코어 구성에 사용되는 이름 집합을 식별한다.

입력. 1장의 테넌트 ID와 적용 설정, 2장의 배포 포함 extension 집합, 대상 SAP Commerce의 플랫폼 소스 또는 JAR.

출력. extension별 활성·제외·미확정 상태, 근거 메서드와 최종 설정값.

## 3.1 코어 컨텍스트를 구성하는 코드의 위치를 찾는다

1. IntelliJ에서 de.hybris.platform.core.Registry를 열고 getCoreApplicationContext()를 찾는다.
2. 메서드가 이미 생성된 컨텍스트를 읽기만 하면 반환에 사용한 객체의 선언과 생성 지점을 확인한다.
3. 프로젝트 소스 검색에서 Loading Spring configuration for 문자열을 검색한다.
4. 로그 문구가 검색되면 로그를 남기는 메서드와 그 호출자를 열어 코어 리소스가 어떤 경로로 Spring에 전달되는지 확인한다.
5. 로그 문구가 소스에 없으면 .application-context 문자열을 플랫폼 소스에서 검색한다.
6. 대상 버전의 소스가 없으면 플랫폼 JAR에서 Registry 및 AbstractTenant의 관련 호출 경로를 읽고, 호출되는 구현 클래스의 디컴파일 결과를 연다.
7. 검색에 걸린 코드가 전역 컨텍스트 또는 다른 컨텍스트를 구성한다면, 호출자가 지정 테넌트의 코어 컨텍스트를 생성할 때 쓰는 메서드를 계속 찾는다.
8. 코드에서 컨텍스트별로 공통 로더를 사용하는 경우, 대상 테넌트 코어 호출이 전달하는 인수와 분기를 기록한다.

### 추적 기록에 넣을 값

|지점       |기록할 내용                                                    |
|---------|----------------------------------------------------------|
|코어 접근점   |`Registry.getCoreApplicationContext()`부터 실제 생성·획득 지점까지의 호출|
|테넌트 전달   |지정 ID가 어떤 테넌트 객체나 설정 객체로 연결되는지                            |
|리소스 목록 생성|실제 생성 메서드, 반환 타입, 목록 생성 시점                                |
|XML 로더 호출|선택된 리소스를 소비하는 클래스·메서드, 대상 컨텍스트의 근거                        |

## 3.2 테넌트별 후보 목록의 실제 생성 규칙을 읽는다

1. 리소스 목록을 만드는 메서드에서 extension 이름을 반복하는 부분을 찾는다.
2. 반복문의 입력 변수를 초기화하는 호출까지 이동한다.
3. Tenant.getTenantSpecificExtensionNames()를 쓰는 경로라면 인터페이스에 이어 대상 테넌트의 구체 클래스를 연다.
4. master가 대상이라면 MasterTenant 구현을, 추가 테넌트라면 그 배포에서 해당 ID에 구성되는 테넌트 구현을 확인한다.
5. 구현이 빌드 목록, 설정 객체, 별도 목록을 조합한다면 각 입력이 어떤 순서로 읽히고 어떤 이름을 추가하거나 제외하는지 기록한다.
6. Utilities.getExtensionNames(), Utilities.getInstalledExtensionNames(Tenant), PlatformConfig 등이 보이면 각 결과에 뒤따르는 필터와 호출자의 사용 방식을 읽는다.
7. API에 반환형이 List로 표시되어 있어도 이 단계에서는 활성 집합의 출처를 판정한다. 실제 순회 순서는 2-2장에서 별도로 확인한다.
8. 코어 로더가 테넌트별 메서드의 결과를 그대로 쓰는지, 플랫폼 extension을 추가하거나 특정 종류를 제외하는지 확인한다.

## 3.3 설정 기반 분기를 정확한 키와 대조한다

1. 위 구현에서 설정값을 읽는 메서드 호출을 찾는다.
2. 호출 인수에 전달하는 실제 키 문자열 또는 키를 조합하는 코드의 구성 요소를 기록한다.
3. 지정 테넌트 설정 파일, local.properties, 대상 배포의 추가 설정층에서 그 키를 찾는다.
4. 미설정, 빈 값, 확장 이름이 명시된 값에서 각각 어떤 분기가 실행되는지 읽는다.
5. 목록에서 모르는 extension 이름을 받았을 때 무시, 기록, 오류 중 어떤 동작을 하는지 확인한다.
6. 이름 정규화가 있다면 실제 코드가 공백과 대소문자를 어떻게 처리하는지 적용한다.
7. 소스에 설정값이 없으면 배포가 추가로 주입하는 입력을 확인한다. 값이 없다는 증거와 파일을 확보하지 못했다는 상태를 구별한다.

## 3.4 활성 상태와 XML 선택 상태를 따로 기록한다

|사례                                        |활성 상태|XML 상태   |
|------------------------------------------|-----|---------|
|대상 코어 로더의 extension 후보 집합에 포함됨            |활성   |4~6장에서 확인|
|대상 테넌트 필터가 명시적으로 제외함                      |제외   |해당 없음    |
|활성 extension에서 선택 가능한 코어 XML이 없음을 6장에서 확인함|활성   |진입 XML 없음|
|실제 테넌트 필터의 설정값이 외부에 있고 자료가 없음             |미확정  |미확정      |

완료 기준. 2장의 각 extension에 대해 지정 테넌트 기준의 활성 상태와 그 근거가 기록되었다.
대상 구현이 실제로 소비하는 목록을 확인했으며, 배포 포함 집합과 테넌트 활성 집합의 차이를 설명할 수 있다.

SAP Commerce 2211 API — Registry, Tenant, AbstractTenant
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/core/Registry.html
https://help.sap.com/doc/789b8a6e1434444da824c2493c6faaa0/2211.4/en-us/de/hybris/platform/core/Tenant.html
https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2211/en-US/de/hybris/platform/core/AbstractTenant.html

## 3.5 프로그램에서의 처리 (master 고정)

[extension-analyzer](../javaTool/extension-analyzer/README.md)와 [spring-bean-analyzer-from-xml](../javaTool/spring-bean-analyzer-from-xml/README.md)은 3장의 조사를 하지 않는다.
테넌트는 1.4절과 같이 **`master`로 고정**하고, 아래를 전제로 한다.

|3장의 절|프로그램의 처리|
|---|---|
|3.1 코어 컨텍스트를 구성하는 코드의 위치|확인하지 않는다. 코어 컨텍스트는 `CORE` 하나로 모델링한다.|
|3.2 테넌트별 후보 목록의 생성 규칙|확인하지 않는다. master 테넌트는 2장에서 선택된 extension을 모두 사용한다고 본다.|
|3.3 설정 기반 분기|테넌트별 설정 파일과 필터 키를 읽지 않는다.|
|3.4 활성 상태와 XML 선택 상태|extension-analyzer의 선택 상태(`EXPLICIT`, `AUTOLOAD`, `PLATFORM`, `DEPENDENCY`)를 그대로 활성으로 쓴다. 진입 XML이 있는지는 4~6장에 해당하는 처리에서 따로 판정한다.|

- 이 전제가 실제 SAP 코드와 일치하는지(3.2의 `MasterTenant` 구현)는 확인하지 않았다.
- master가 아닌 테넌트를 분석하려면 3장의 처리를 새로 구현해야 한다.

## 4장에서 사용할 내용
