# Commerce beans.xsd와 Spring Bean XML 구분

## 확인한 문제

개발환경 Bean 분석에서 BEAN_XML_INSPECTION_ERROR가 144건 보고되었고,
사진에 보이는 상세는 Expected Spring beans root였다.
사용자 제공 acceleratorbackoffice-beans.xml은 네임스페이스 없는 beans 루트와
xsi:noNamespaceSchemaLocation="beans.xsd"를 사용했다.
주석에는 Example Data POJO와 class/property type 예제가 있었다.
이는 Spring 네임스페이스의 Bean 설정과 구분해야 할 Commerce 데이터 클래스 정의 형식이다.

기존 SpringXmlAnalyzer는 네임스페이스가 없고 루트 이름이 beans인 문서도 Spring 후보로 분류했다.
반면 새 Bean 분석기는 Spring 네임스페이스만 인정해서 두 단계의 분류 기준이 달랐다.
이 때문에 해당 파일이 Bean 분석으로 전달된 뒤 루트 오류로 표시되었다.
SAP 파일이 잘못되었다는 증거가 아니라 분석기의 분류 문제였다.

## 변경

- 루트가 beans이고 기본 네임스페이스가 없으며 noNamespaceSchemaLocation이 beans.xsd 또는 /beans.xsd로 끝나면
  spring=false, parse_status=NOT_SPRING_BEAN_GENERATION_XML로 기록한다.
- 소스 XML과 도달한 JAR XML에 같은 분류 기준을 적용한다.
- 해당 문서는 spring-xml-files.csv 또는 spring-jar-xml-files.csv에서 분류를 확인할 수 있다.
- XML 파일명만으로 제외하지 않는다. 실제 Spring 네임스페이스 문서는 -beans.xml 이름이어도 분석한다.
- 네임스페이스가 없는 기존 DTD 기반 Spring XML은 계속 허용한다. 외부 DTD는 읽지 않는다.
- Bean 분석기가 파일 변경 등으로 해당 형식을 직접 받으면 오류가 아닌 SKIPPED_BEAN_GENERATION_XML로 기록한다.
- CSV 개수는 21개다. 데이터 클래스 생성 분석 자체는 이번 범위에 포함하지 않는다.

NOT_SPRING_BEAN_GENERATION_XML은 루트/스키마에 의한 문서 종류 판정이지 해당 문서 전체의 문법 검증 결과가 아니다.
다른 namespace-free 독자 형식이나 위 패턴 이외의 스키마를 모두 자동 구분하는 기능은 아니다.

## 예상되는 결과

위 형식이 원인이었던 루트 오류는 제거되어야 한다. 144건 모두 같은 원인인지는 현재 확인하지 않았으므로
오류 0건을 보장하지 않는다. 재실행 후 남은 BEAN_XML_INSPECTION_ERROR는 별도 원인으로 확인해야 한다.
Spring XML 수와 관련 Bean 집계도 분류 수정 또는 legacy 문서 지원에 따라 달라질 수 있다.
CUSTOM_NAMESPACE_NOT_MODELED는 별도 범위 한계이며 이번 수정으로 전부 없어지지 않는다.

## 로컬 재현 자료

사용자 요청에 따라 사진에서 주석을 제외한 XML을 다음 위치에 저장했다.
C:/hybris/bin/modules/core-accelerator/acceleratorbackoffice/resources/acceleratorbackoffice-beans.xml
사진의 ISO-8859-1 선언과 인코딩을 유지했다. 활성 내용은 빈 beans 루트다.

## 검증

BeanXmlDocumentsTest: 소스/JAR 내 데이터 정의 제외, Spring namespace 우선,
파일명에 의존하지 않는 분류, namespace-free DTD Spring 유지, Bean 목록의 잘못된 DTO 등록 방지.
기존 10개 테스트도 함께 실행한다.
