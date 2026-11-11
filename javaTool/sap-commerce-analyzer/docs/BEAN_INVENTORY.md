# Spring XML Bean 선언과 명시적 참조 목록

## 최종 목적과 이번 단계

원래 목적은 현행 Accelerator 요청에서 Controller → Facade → Service → DAO 호출을
추적해 현행 기능 조사와 이행 검토에 사용할 자료를 만드는 것이다.

이번 단계까지의 연결:

1. Extension 선택 여부 파악 → 조사할 소스 범위를 정한다.
2. Spring 설정 진입점/import 파악 → XML을 읽는 경로 후보를 정한다.
3. 이번 Bean 목록 → XML에 어떤 클래스가 선언되고 어떤 Bean 이름을 명시적으로 참조하는지 기록한다.
4. 이후 최종 등록/참조 해석 및 CallTrace 설계 → 실제 요청의 메서드 호출 확인으로 이어간다.

이번 결과는 3번까지다. Bean 참조는 메서드 호출이 아니므로 Service → DAO 호출 그래프라고 해석하면 안 된다.

## 분석 범위

- 선택된 Extension의 XML 중 기존 Spring XML 분석에서 발견·파싱한 Spring 문서
- 기존 분석에서 도달하여 발견·파싱한 선택된 JAR 내부 Spring XML
- 같은 XML은 XML 경로/JAR URI 기준 한 번만 선언 목록을 만든다.
- 각 XML에 기존 로딩 도달성의 entry_id와 modeled_context 목록을 연결한다.
- 등록 진입점에서 도달하지 않은 소스 Spring XML도 목록에는 포함하되 NO_MODELED_ENTRY_REACHES_XML로 구분한다.
- 비선택 Extension 진단 후보를 Bean 분석에 승격하지 않는다.

## 결과 파일: 기존 18개 + 신규 3개 = 21개

| 파일 | 내용 |
| --- | --- |
| spring-beans.csv | Bean 선언, 원본 XML/JAR 경로, Extension, 원문 id/name/class/parent/scope/abstract/factory, 내부 Bean 관계, profile 및 defaults 경로, 잠재 진입점/컨텍스트 |
| spring-bean-references.csv | 소유 Bean 선언 키, 참조 종류, property/constructor 등의 위치, 명시적 대상 이름, profile, 잠재 컨텍스트 |
| spring-bean-issues.csv | 파일 읽기/파싱 실패와 custom namespace 등 해석 범위의 한계 |

클래스나 속성 값에 ${...}가 있어도 원문으로 남긴다. 속성 치환, 부모 Bean 상속 적용, 최종 구현 클래스 추측은 하지 않는다.
Bean의 id가 없어도 선언 키(source XML + 문서 내 Bean 순번)를 부여한다.
이 키는 분석용이며 Spring이 실제 생성하는 Bean 이름이 아니다. bean_ordinal은 줄 번호가 아니다.

명시적으로 적힌 값이 없는 scope/abstract/factory 등은 빈 셀로 두고 effective default라고 단정하지 않는다.
declared_defaults_chain과 declared_profile_chain은 상위 XML 선언을 기록한 것이며 적용 결과를 계산한 값이 아니다.
name 속성의 별칭 목록도 원문 보존이다.

## 기록하는 명시적 관계

- property/constructor-arg의 ref 속성
- ref의 bean/local/parent 속성 (parent는 별도 종류로 보존)
- list/set 등 아래의 ref 요소
- map entry의 key-ref/value-ref
- p/c namespace의 -ref 속성
- parent Bean 정의, factory-bean, depends-on
- lookup-method, replaced-method의 명시 Bean 이름
- 내부 Bean의 선언 키와 바깥 Bean의 연결
- alias 선언 (member_or_alias=새 별칭, declared_target=기존 이름)
- idref (IDREF_NAME_*): 객체 주입이 아니라 Bean 이름 문자열이라는 점을 구분

모든 binding_status는 NOT_RESOLVED다. 현재 단계에서 대상을 못 찾았다는 뜻이 아니라
아직 최종 대상 Bean을 결정하는 처리를 하지 않는다는 뜻이다.
동일 이름 중복, alias chain, CORE/WEB 부모자식 관계, override 순서도 결정하지 않는다.

## 제약 및 보안

- @Autowired, component-scan, Java @Bean, custom namespace가 등록하는 Bean은 추출하지 않는다.
  custom namespace의 요소는 CUSTOM_NAMESPACE_NOT_MODELED로 기록한다. 이 상태는 SAP 설정 오류를 의미하지 않는다.
- 활성 profile, autowire/defaults/상속 최종값, factory-method 반환 타입, 조건부/동적 등록을 재현하지 않는다.
- 일반 property value, 생성자 literal 값, Bean 내부 텍스트 값은 결과에 내보내지 않는다.
  클래스/이름/명시 참조/설정 메타데이터는 기록한다.
- 분석 대상 소스와 JAR는 읽기만 하며 Bean을 생성하거나 Java 클래스를 로드하지 않는다.
- JAR를 풀지 않고 엔트리 스트림으로 읽는다. XML당 16 MiB, XML 요소 깊이 256 제한.
- 외부 DTD/스키마/엔티티 로딩은 차단한다. 사용자 정의 엔티티 참조는 실패로 기록한다.
- XML 파싱이 완료된 후 선언 수집을 시작하므로 파싱 실패 파일의 일부 선언을 성공 목록에 섞지 않는다.
- 첫 XML 분석 이후 파일이 바뀌면 Bean 분석과 앞선 import 분석 사이에 차이가 생길 수 있다.
  조사 중 파일을 수정했다면 재실행한다.

## 실행 및 나중에 확인할 항목

기존대로 ExtensionScanner를 실행한다. 추가 인자나 외부 라이브러리가 필요하지 않다.
콘솔에 Spring Bean Declaration Inventory (STATIC XML) 집계가 추가되고 CSV는 21개다.

우선 spring-beans.csv의 owner_extension을 자신의 Storefront/Facades/Core로 좁혀
declared_class를 보면 XML에 등록된 클래스 후보를 볼 수 있다.
특정 선언의 declaration_key로 spring-bean-references.csv를 찾으면 명시적 참조를 확인할 수 있다.
잠재 컨텍스트가 있어도 실제 등록은 NOT_EVALUATED이며 호출은 NOT_EVALUATED다.

## 검증

SpringBeanAnalyzerTest: 일반/부모/내부 Bean, 별칭, factory/default 메타데이터,
profile별 동일 이름, p/c namespace, property/constructor/list/map/ref/idref,
JAR XML, 비선택 제외, 도달성 표시, custom namespace 한계, literal 비노출,
변경된 XML 파싱 실패와 외부 엔티티 차단을 검증한다.
ActiveExtensionAnalyzerTest는 ExtensionScanner의 CSV 21개 출력을 검증한다.
기존 import/선택/JAR/로딩 보고서 테스트도 실행한다.

규칙 참고(Bean 선언과 명시적 참조의 구분에 한정; 대상 설치 버전의 런타임 전체를 재현하지 않음):
- https://docs.spring.io/spring-framework/reference/core/beans/definition.html
- https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-properties-detailed.html
