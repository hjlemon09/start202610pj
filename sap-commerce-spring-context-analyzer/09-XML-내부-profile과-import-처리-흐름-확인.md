> SAP Commerce 2211 지정 테넌트의 코어 Spring XML 조사 절차서 — 9/13장. 이전: [8. 같은 extension 안의 진입 XML 순서 확인](08-같은-extension-안의-진입-XML-순서-확인.md) · 다음: [10. 최종 검증과 결과물 작성](10-최종-검증과-결과물-작성.md)

# 9. XML 내부 profile과 import 처리 흐름 확인

작업 목적. 진입 XML을 읽을 때 어떤 하위 XML을 어느 위치에서 처리하는지 재구성한다.

입력. 6장과 8장의 진입 파일, 대상 Spring 버전, 해당 코어 컨텍스트의 환경 및 리소스 로더.

출력. import별 활성 여부, 실제 위치, 처리 이벤트, 예외 또는 미확정의 이유.

## 9.1 대상 Spring XML 로더와 환경을 확인한다

1. 대상 배포에서 Spring Beans와 Spring Core JAR를 찾는다.
2. 파일명만으로 버전이 불명확하면 JAR의 META-INF/MANIFEST.MF 또는 의존성 자료에서 확인한다.
3. org.springframework.beans.factory.xml.XmlBeanDefinitionReader의 loadBeanDefinitions 호출 경로를 연다.
4. 실제로 사용하는 문서 리더가 DefaultBeanDefinitionDocumentReader인지 확인한다.
5. 기본 구현을 사용하면 doRegisterBeanDefinitions, parseBeanDefinitions, importBeanDefinitionResource를 순서대로 읽는다.
6. 사용자 정의 문서 리더나 리소스 로더를 주입한다면 해당 구현의 선택 코드와 동작을 적용한다.
7. 로더가 참조하는 Spring Environment가 어디서 설정되는지 추적한다.
8. 활성 profile, 기본 profile, placeholder의 값이 SAP Commerce 프로퍼티나 JVM·배포 설정에서 어떻게 연결되는지 확인한다.
9. 같은 테넌트의 일반 설정에 값이 있어도 Spring Environment의 해당 시점에 들어오는지 확인한다.

## 9.2 profile 활성 여부를 먼저 판정한다

1. XML 최상위 요소와 중첩 <beans>의 profile 속성을 찾는다.
2. 해당 문서 리더가 profile 문자열을 해석하는 구현과 사용 API를 확인한다.
3. 현재 적용되는 profile 목록과 비교해 블록이 읽히는지 판정한다.
4. 부모 블록이 비활성이면 그 아래 import도 처리되지 않는 상태로 기록한다.
5. 활성 profile 입력이 확인되면 해당 값을 기록한다.
6. 입력이 미확정이면 어떤 조건에서 그 import가 실행되는지 남긴다.
7. profile에 쉼표, 구분자, 부정 기호 등이 있으면 대상 구현의 해석 규칙을 확인한다.
8. profile 조건이 없으면 문서 리더의 일반 요소 처리로 진행한다.

## 9.3 import의 원문과 치환 결과를 기록한다

1. XML 요소를 문서 순서대로 읽다가 <import resource="…">를 찾는다.
2. 부모 파일 경로, 줄 번호 또는 요소 위치, resource 원문을 기록한다.
3. ${…}가 있다면 그 import를 처리하는 메서드가 어느 Environment에서 값을 읽는지 확인한다.
4. 확인된 최종값으로 문자열을 치환하고 치환 전·후를 함께 남긴다.
5. 해결되지 않은 필수 값에서 오류를 내는 구현이라면 실제 오류 분기를 확인한다.
6. 같은 파일에 profile 분기와 placeholder가 모두 있다면 profile을 먼저 판정하고, 활성 분기의 값 해석을 진행한다.
7. 값을 여러 곳에서 설정한 경우 코어 XML 리더가 사용하는 환경과 적용 우선순위로 최종값을 정한다.

## 9.4 상대 경로·절대 경로·패턴의 대상을 판정한다

|resource 형식                        |확인할 코드와 입력                                                 |확인할 결과                           |
|-----------------------------------|-----------------------------------------------------------|---------------------------------|
|`spring/common.xml`                |`importBeanDefinitionResource`의 상대 리소스 분기와 현재 XML의 Resource|현재 XML을 기준으로 실제 선택된 파일 또는 JAR 엔트리|
|`classpath:...`                    |리소스 로더의 단일 위치 검색과 클래스 로더                                   |선택된 실제 리소스                       |
|`file:...`                         |대상 실행 환경의 파일 경로와 관련 리소스 구현                                 |해석된 절대 경로                        |
|`classpath*:...` 또는 wildcard가 있는 패턴|해당 로더의 `getResources` 처리, 검색 대상, 반환 순서                     |매칭된 전체 리소스와 실제 열거 순서 또는 미확정      |
|외부 URL·커스텀 프로토콜                    |해당 리소스 핸들러와 고정된 자료가 있는지 확인                                 |정적 자료로 내용을 확보했는지, 외부 입력이 필요한지    |

1. 상대 리소스의 경우 문서 리더의 현재 Resource 기준 해석과 대체 경로로 넘어가는 조건을 확인한다.
2. PathMatchingResourcePatternResolver.getResources를 사용할 경우 대상 Spring 버전과 실제 클래스 로더 정책을 적용한다.
3. 패턴이 여러 후보를 반환하면 파일명순과 반환 순서를 각각 확인한다.
4. 소스에 해당 파일이 있어도 대상 배포 클래스패스의 실물에서 선택된 상대 경로를 다시 확인한다.
5. 상대 파일이 없을 때 클래스패스의 다른 동일 이름이 자동 선택된다고 가정하기 전에 코드의 fallback을 확인한다.
6. 특정 패턴의 전체 매칭 집합을 알지만 순서가 미확정인 경우, 목록과 순서를 서로 다른 상태로 기록한다.
7. 네임스페이스나 커스텀 핸들러가 별도 방식으로 리소스를 찾으면 선언적 import와 별개의 호출 위치를 기록한다.

## 9.5 중첩 처리와 반복 참조를 기록한다

1. 활성 import의 실제 대상을 열고 9.2~9.4를 다시 적용한다.
2. 하위 XML을 읽은 뒤 부모 XML의 그 다음 요소로 복귀한다.
3. 동일 파일이 여러 import에서 사용되면 각 호출 위치를 남긴다.
4. 현재 호출 스택에 있는 파일로 다시 진입한다면 순환 경로를 기록한다.
5. 대상 XmlBeanDefinitionReader의 순환 검사와 예외 전파를 확인해 실패 위치를 판정한다.
6. 이미 처리가 끝난 파일을 뒤에서 다시 참조한 경우, 현재 호출 스택의 순환과 구별한다.
7. 중복을 제거하는 구현이 추가되었는지 확인하고 실제 읽기 횟수를 기록한다.
8. 디렉터리 목록을 평면으로 정렬하면 부모 문서로 복귀하는 위치가 사라지므로 이벤트 표에는 부모와 위치를 유지한다.

## 9.6 파일 진입과 문서 요소 처리를 구별한다

|순서|이벤트                       |출처                  |
|-:|--------------------------|--------------------|
|1 |`root.xml` 진입             |extension 내부 진입 순번 1|
|2 |`root.xml`의 import 앞 선언 처리|`root.xml:12`       |
|3 |`extra.xml` import 진입     |`root.xml:24`       |
|4 |`extra.xml` 처리 완료 후 부모로 복귀|`root.xml:24` 다음 요소 |
|5 |`root.xml`의 나머지 선언 처리     |`root.xml:30`       |
|6 |`overrides.xml` 진입        |extension 내부 진입 순번 2|

파일 자체의 진입 순서와 빈·alias 등의 선언이 처리되는 순서는 같은 표에서 서로 다른 이벤트로 기록할 수 있다.
정적분석 결과를 빈 재정의 분석에 사용할 때는 부모 문서로 복귀한 이후의 선언도 함께 고려한다.

완료 기준. 모든 활성 import에 대해 현재 문서, 선언 위치, profile 상태, 치환 결과, 실제 리소스, 중첩 경로, 복귀 위치가 확인되었다.
wildcard나 외부 입력으로 인한 미확정은 영향을 받는 import와 하위 XML을 지정해 남긴다.

Spring Framework 6.2 API — DefaultBeanDefinitionDocumentReader
https://www.springframework.org/spring/docs/6.2.7/javadoc-api/org/springframework/beans/factory/xml/DefaultBeanDefinitionDocumentReader.html

Spring Framework 6.2 API — XmlBeanDefinitionReader
https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/beans/factory/xml/XmlBeanDefinitionReader.html

Spring Framework 6.2 API — PathMatchingResourcePatternResolver
https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/io/support/PathMatchingResourcePatternResolver.html
