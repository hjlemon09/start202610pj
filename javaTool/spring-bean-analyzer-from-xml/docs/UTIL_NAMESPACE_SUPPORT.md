# util 선언 지원과 미지원 네임스페이스 구분

## 목적

분석기는 XML의 Bean 선언과 명시적 참조를 정적으로 수집한다.
기존에는 util:list 등의 이름 있는 선언이 빠졌고 그 안의 ref 소유자도 해당 util 선언으로 연결되지 않았다.
이번 변경은 컬렉션/상수 선언을 목록에 포함하고 컬렉션 안의 명시 참조를 해당 선언에 연결한다.
실제 Bean 생성/상수 평가/컬렉션 생성/메서드 호출은 하지 않는다.

## 조건 → 결과 예시

```xml
<util:list id="populators">
    <ref bean="firstPopulator"/>
</util:list>
<bean id="converter" class="demo.Converter">
    <property name="populators" ref="populators"/>
</bean>
```

1. util:list 발견 → declaration_kind=UTIL_LIST, declared_id=populators인 선언을 기록한다.
2. 내부 ref 발견 → 목록 선언 키를 소유자로, firstPopulator를 명시 대상으로 기록한다.
3. converter의 property ref 발견 → 기존대로 populators 이름을 기록한다.
4. 따라서 대상 이름에 해당하는 선언 자료가 목록에 생긴다.
5. 하지만 alias/override/Context를 고려한 최종 대상 바인딩은 아직 하지 않는다.
   binding_status=NOT_RESOLVED는 그대로 유지된다.

## 이번 지원 범위

| 태그 | 선언 종류 | 추가 메타데이터 |
| --- | --- | --- |
| util:list | UTIL_LIST | list-class, value-type |
| util:set | UTIL_SET | set-class, value-type |
| util:map | UTIL_MAP | map-class, key-type, value-type |
| util:constant | UTIL_CONSTANT | static-field |

실제 태그 접두사가 util이 아니라 u 등이어도 네임스페이스 URI로 판별한다.
scope는 기존 declared_scope 열에 원문으로 기록한다. id, profile, defaults, 잠재 진입점도 원문/기존 근거를 보존한다.
util 태그에는 명시적인 class 속성이 없으므로 declared_class를 빈 값으로 둔다.
FactoryBean 구현 클래스나 실제 반환 타입을 declared_class로 만들어 넣지 않는다.

명시 id가 없는 선언은 declared_id가 빈 값이다. 분석용 declaration_key만 부여한다.
Spring이 생성할 이름이나 constant의 기본 이름을 재현하지 않는다.
새 선언이 추가되어 문서의 bean_ordinal/분석 키 번호는 이전 실행과 달라질 수 있다.
이 키는 실행 간 영구 식별자가 아니다.

## 참조와 중첩 처리

- util 목록/집합 안의 ref와 내부 bean은 해당 util 선언을 소유자로 기록한다.
- util map의 key-ref/value-ref 및 중첩 ref는 map 선언을 소유자로 기록한다.
- 일반 Bean property 안의 util 선언은 INNER_UTIL_DECLARATION 관계로 바깥 Bean과 연결한다.
- 중첩 util 선언에도 각각 키를 부여한다. 안쪽 선언 종료 뒤의 형제 property는 원래 소유 Bean에 연결된다.
- util:constant의 static-field는 상수 메타데이터다. Bean 이름 참조로 기록하지 않고 Java 필드를 읽지도 않는다.
- literal value 내용은 기존과 같이 내보내지 않는다.
- 같은 XML을 여러 컨텍스트에서 도달해도 원본 선언은 기존 기준대로 한 번 수집하고 잠재 문맥 목록을 연결한다.
- 소스 XML과 기존 분석에서 도달한 선택된 JAR XML에 같은 처리를 적용한다.

## CSV 변경

CSV 총수는 **21개**로 유지된다.
spring-beans.csv 끝에 다음 두 열을 추가했다. 기존 열은 순서와 의미를 유지한다.

- declaration_kind: 일반 bean은 BEAN, util 선언은 UTIL_LIST/UTIL_SET/UTIL_MAP/UTIL_CONSTANT
- declared_namespace_attributes: 위 표의 속성만 원문으로 기록

spring-bean-references.csv에는 기존 구조로 새 util 소유 관계와 내부 참조를 기록한다.
spring-bean-issues.csv는 미지원 종류를 아래처럼 구분한다.

| status | 의미 |
| --- | --- |
| ANNOTATION_PROCESSING_NOT_MODELED | context:annotation-config의 처리 효과/내부 등록 미분석 |
| COMPONENT_SCAN_NOT_MODELED | context:component-scan으로 생성되는 Bean 미수집 |
| CONTEXT_CONFIGURATION_NOT_MODELED | 그 밖의 context 설정 효과 미해석 |
| AOP_CONFIGURATION_NOT_MODELED | AOP/프록시 설정의 적용 효과 미해석 |
| INTEGRATION_FLOW_NOT_MODELED | integration 및 integration/file 등 흐름/자동 등록 미분석 |
| UTIL_TAG_NOT_MODELED | properties/property-path 등 이번 4종 밖의 util 태그 미지원 |
| CUSTOM_NAMESPACE_NOT_MODELED | 그 밖의 네임스페이스 미지원 |
| UTIL_DECLARATION_INCOMPLETE | util:constant의 static-field가 비어 있음 |

지원하지 않는 태그의 실제 처리를 구현한 것이 아니라 한계를 구분한 것이다.
따라서 경고 건수가 반드시 0이 되어야 하는 것은 아니다.
같은 문서의 동일 네임스페이스/태그 경고는 한 번만 기록하는 기존 기준을 유지한다.
기존 명시적 bean/ref가 미지원 태그 내부에 있으면 그 명시적 XML 구문 자체는 계속 수집할 수 있지만
네임스페이스 처리기가 만드는 전체 등록과 같다고 보장하지 않는다.

## 실행과 확인

기존대로 ExtensionScanner를 실행한다. 새 인자/라이브러리는 없다.
콘솔 Bean 집계에 Util declarations와 Inventory issues의 status별 건수를 추가했다.
summary.csv에는 bean_util_declarations를 추가했다.

확인할 결과:

1. spring-beans.csv에 UTIL_LIST/UTIL_SET/UTIL_MAP/UTIL_CONSTANT 행이 있는가?
2. 해당 선언의 키로 spring-bean-references.csv에서 내부 ref를 찾을 수 있는가?
3. BEAN_XML_INSPECTION_ERROR는 여전히 0건인가?
4. 미지원 상태가 Annotation/AOP/Integration 등으로 구분됐는가?

## 검증

SpringUtilNamespaceTest에서 태그 접두사 변경, 원문 scope/type/profile,
소스/JAR, 익명/중첩 컬렉션, 내부 Bean, 형제 property 소유자 복원,
map 키/값/중첩 참조, 상수와 Bean 참조 구분, 진단 중복 방지, 미지원 상태 분류,
literal 비노출, CSV 추가 열 및 바인딩 미확정 표시를 검증한다.
기존 회귀 테스트와 함께 실행한다.

참고: https://docs.spring.io/spring-framework/reference/core/appendix/xsd-schemas.html
공식 문서의 XML 표현 의미를 참고했으며 설치된 Spring 전체 런타임 처리기를 재현하지 않는다.
