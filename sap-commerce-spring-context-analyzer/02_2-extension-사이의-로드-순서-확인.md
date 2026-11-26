> SAP Commerce 2211 지정 테넌트의 코어 Spring XML 조사 절차서 — 2-2장. 이전: [2. 배포에 포함된 extension 집합 확인](02-배포에-포함된-extension-집합-확인.md) · 다음: [3. 지정 테넌트의 활성 extension 집합 확인](03-지정-테넌트의-활성-extension-집합-확인.md)

# 2-2. extension 사이의 로드 순서 확인

## 2장에서 확정한 내용

## 2-2장의 내용

작업 목적. 지정 테넌트 코어 로더가 extension을 어떤 순서로 처리하는지 확정한다.

입력. 2장의 의존성 관계, 3장의 테넌트별 목록 생성 경로, 대상 배포의 순서가 담긴 자료.

출력. 입증한 extension 순번, 부분 선후관계, 순서가 미확정인 쌍과 원인.

## 2-2.1 코어 로더가 읽는 최종 자료구조를 찾는다

1. 3장에서 확인한 코어 리소스 목록 생성 메서드를 다시 연다.
2. extension을 순회하는 반복문, 메서드 호출 또는 스트림을 찾는다.
3. 순회하는 객체가 리스트, 집합, 맵의 키, 스트림 중 무엇인지 확인한다.
4. 변수 선언과 값을 채우는 메서드까지 거슬러 올라간다.
5. 어떤 클래스가 기본 빌드 순서를 제공하는지, 데이터가 어디에서 생성되는지 기록한다.
6. PlatformConfig.getExtensionInfosInBuildOrder()는 대상 2211에서 찾을 수 있는 탐색 지점이다. 해당 배포의 코어 로더가 이를 실제로 호출하는지 확인한다.
7. 호출에 여러 목록이 있다면 연결 순서, 중복 처리, 우선순위 정렬을 확인한다.
8. 테넌트 필터가 기존 순서를 유지하며 추리는지, 별도의 목록으로 복사한 뒤 다시 정렬하는지 확인한다.
9. 리소스 선택 과정에서 일부 extension을 건너뛸 때, 나머지 순서가 바뀌는지 확인한다.

## 2-2.2 빌드 자료의 순서를 재사용할 수 있는지 판단한다

1. 대상 배포의 extension 목록이나 빌드 메타데이터를 연다.
2. 그 자료의 생성기를 찾아, 행 순서가 빌드 순서인지 이름순 인벤토리인지 확인한다.
3. 빌드 순서라면 코어 로더가 같은 자료를 읽거나 그 순서를 재사용하는지 확인한다.
4. 자료에 parallel build의 그룹이 있다면 그룹과 그룹 내부의 의미를 구분한다.
5. 로더가 일렬 목록을 별도로 만든다면 목록 생성 코드에서 동률 처리 기준을 확인한다.
6. 특정 빌드의 정렬 결과가 파일에 남아 있으면 해당 파일의 배포 버전과 1장의 버전을 대조한다.
7. 같은 커밋이라도 적용 설정이나 빌드 산출물이 다르면 각 배포에 해당하는 순서를 따로 기록한다.

## 2-2.3 의존성으로 보장되는 선후관계를 정리한다

1. A/extensioninfo.xml에 <requires-extension name="B"/>가 있고 실제 빌드에 그 관계가 적용된다면 B < A를 기록한다.
2. C < B도 확인했다면 앞선 관계와 결합해 C < A를 기록한다.
3. 코어 로더가 SAP 문서에 설명된 빌드 순서와 어떻게 연결되는지 해당 구현 또는 산출물에서 대조한다.
4. 두 extension이 서로 선행할 근거가 없으면 임의로 이름순 또는 폴더순을 부여하지 않는다.
5. 빌드의 의존성 처리 결과가 여러 순서를 허용하더라도, 대상 배포의 동률 결정 입력을 확보하면 그 배포의 순서를 추가로 확정할 수 있다.

|증거                                                         |확인된 결과                              |다음 작업                        |
|-----------------------------------------------------------|------------------------------------|-----------------------------|
|`basecatalog < melcocore`와 `basecatalog < regionalcore`만 확인|`basecatalog`는 두 extension보다 선행     |커스텀 두 항목의 상대 순서를 판단할 자료를 찾는다.|
|대상 로더가 빌드 자료의 순서를 유지해 필터링함                                 |필터 후에도 남은 extension의 상대 순서를 계산할 수 있음|각 XML에 extension 순번을 연결한다.   |
|로더 입력이 정렬되지 않은 디렉터리 열거에 의존하나 해당 열거 결과가 없음                  |일부 상대 순서 미확정                        |실제 입력을 보존한 배포 자료 여부를 확인한다.   |
|목록에 순번이 있지만 생성 근거가 이름순 출력임                                 |이름순 참고 인벤토리                         |다른 근거로 로더 순서를 확인한다.          |

## 2-2.4 순서 미확정의 영향 범위를 기록한다

• 출력하려는 전체 순서가 달라지는 extension을 쌍으로 기록한다.
• 예를 들어 melcocore < regionalcore와 regionalcore < melcocore가 모두 가능한 경우 두 조건을 적는다.
• 두 extension의 진입 XML과 import 목록은 각각 확정된 상태로 유지한다.
• 향후 동일 ID나 alias 재등록에 영향을 줄 수 있는 사례는 어떤 ID가 어느 출처에서 중복되는지 별도 메모를 남긴다.
• 전체 목록에 절대 순번을 붙이려면 해당 순서를 입증할 수 있는 자료가 필요하다.
• 부분 순서만 입증했다면 A < B 형태의 관계와 미확정 쌍을 결과표에 제공한다.

완료 기준. 전체 확정은 활성 extension의 최종 순회 순번과 모든 순서 결정 근거가 확보된 경우에만 부여한다.
근거가 부분적이면 입증한 선후관계와 부족한 입력을 작성하고 순서의 전체 상태를 미확정으로 기록한다.

SAP Commerce 2211 API — PlatformConfig
https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2211/en-US/de/hybris/bootstrap/config/PlatformConfig.html

## 2-2.5 프로그램에서의 처리 (extension-analyzer)

2-2장의 선후관계는 [extension-analyzer](../javaTool/extension-analyzer/README.md)가 계산한다.
2장의 선택 판정 바로 다음에, **선택된 extension끼리만** 계산한다.

### 구성 위치

|소스|하는 일|
|---|---|
|`ExtensionInfoParser.java`|`extensioninfo.xml`에서 `<requires-extension>`과 `<meta key="load-prior-to" value="…"/>`를 읽는다.|
|`ExtensionOrder.java`|선택된 extension 사이의 보장된 순서를 부분 순서로 만든다. 직접 관계를 모은 뒤 전이적으로 펼치고, 서로 도달하는 쌍(순환)은 미확정으로 둔다.|
|`ExtensionAnalyzer.java`|선택 판정이 끝난 뒤 선택된 extension만 골라 `ExtensionOrder`에 넘긴다.|
|`ReportWriter.java`|순서 결과를 CSV로 쓴다.|

### 2-2장의 절과의 대응

|2-2장의 절|프로그램의 처리|
|---|---|
|2-2.1 코어 로더가 읽는 최종 자료구조|**하지 않는다.** 실제 코어 로더의 순회 순서(`PlatformConfig.getExtensionInfosInBuildOrder()` 등)는 재현하지 않는다.|
|2-2.2 빌드 자료의 순서 재사용|**하지 않는다.** 빌드 결과나 메타데이터의 순서는 읽지 않는다.|
|2-2.3 의존성으로 보장되는 선후관계|한다. 아래 세 가지 관계만 "보장된 순서"로 쓰고, 전이적으로 펼친다.<br>• B가 `requires-extension` A → A가 먼저 (`REQUIRES`)<br>• A는 `bin/platform/ext` 아래, B는 아님 → A가 먼저 (`PLATFORM`)<br>• A의 `load-prior-to`에 B → A가 먼저 (`LOAD_PRIOR_TO`)<br>• 다른 extension을 거쳐서만 이어지는 관계 (`TRANSITIVE`)|
|2-2.4 순서 미확정의 영향 범위|한다. 위 관계로 정해지지 않는 쌍은 이름순·폴더순으로 정하지 않고 미확정으로 둔다. 순환 안의 extension끼리도 미확정이다. 전체 절대 순번은 부여하지 않는다.|

### 결과물

|파일|내용|
|---|---|
|`extension-load-order.csv`|보장된 쌍만 한 행씩 기록한다. 열: `extension`(먼저 로드), `loads_before`(나중 로드), `basis`(`REQUIRES` / `PLATFORM` / `LOAD_PRIOR_TO` / `TRANSITIVE`, 직접 근거가 여럿이면 `;`로 연결). **이 파일에 없는 쌍은 순서 미확정이다.**|
|`extensions.csv`|`load_prior_to`(선언된 이름), `platform_extension`(`bin/platform/ext` 아래 여부), `load_order_cycle`(순환에 속해 순서가 미확정인지) 열|
|`summary.csv`|`load_order_pairs`(보장된 쌍 수), `load_order_cycle_extensions`(순환에 속한 extension 수), `load_order_basis`(사용한 근거)|

spring-bean-analyzer-from-xml은 이 `extension-load-order.csv`와 `extensions.csv`의 `load_order_cycle`을 읽어(`com.extension.ExtensionOrder`), 같은 이름 빈의 최종 승자를 판정할 때 extension 사이의 순서로 쓴다. 표에 없는 쌍에서 같은 이름이 정의되면 승자를 고르지 않고 `UNDETERMINED_ORDER`로 남긴다.

## 3장에서 사용할 내용
