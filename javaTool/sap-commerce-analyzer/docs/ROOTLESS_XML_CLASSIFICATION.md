# 루트 요소가 없는 widgets 파일 진단 정리

사용자가 공유한 paidaddon-backoffice-widgets.xml은 XML 선언과 저작권 주석 이후,
widgets 설정 전체가 5행의 주석 시작부터 16행의 주석 끝까지 주석 처리돼 있었다.
실제 루트 요소가 없어 기존 파서는 파일 끝에서 XML_INSPECTION_ERROR를 기록했다.

이것은 닫는 태그를 자동으로 보충할 사례가 아니다. 원본 주석을 해제하면 기능 동작이
달라질 수 있으므로 원본은 수정하지 않았다. 또한 내용은 Spring beans 설정이 아닌
Backoffice widgets 설정이다.

개선 후 루트 요소를 만나기 전에 파싱이 실패하면, 1MB 이하 파일에서 원래 인코딩으로
본문을 다시 읽는다. XML 선언을 제외하고 메모리상의 임시 루트 안에서 나머지를 검증한다.
요소나 일반 텍스트 없이 올바른 주석/공백/처리 지시만 있는 경우에 한해
SKIPPED_NO_ROOT_ELEMENT로 분류한다. 외부 리소스를 읽지 않고 임시 파일도 쓰지 않는다.
깨진 주석, 잘린 태그 등은 XML_INSPECTION_ERROR로 유지한다.

기록 위치:

- spring-xml-files.csv: is_spring_xml=false, parse_status=SKIPPED_NO_ROOT_ELEMENT
- spring-import-issues.csv: 같은 상태와 제외 사유
- Console: Skipped rootless XML
- summary.csv: skipped_rootless_xml

이는 XML이 정상이라고 판단하거나 실제 Backoffice에서 사용되지 않는다고 확인한 것이 아니다.
루트 요소가 없어 Spring import 분석에서 제외했다는 사실을 구분한 것이다.
전체 진단 수는 그대로일 수 있으며 CSV 개수도 14개로 유지한다.

검증은 Java 21의 RootlessXmlTest에서 주석 처리된 widgets, 공백, UTF-16 파일의 분류와
깨진 주석/태그/텍스트 오류 유지 및 원본 불변을 확인했다. 기존 테스트도 함께 실행한다.
실제 개발 환경에서는 기존 오류 1건이 SKIPPED_NO_ROOT_ELEMENT로 바뀌는지 확인하면 된다.

이후 본 작업은 선택된 JAR에서 발견한 XML 본문을 읽고 내부 import를 따라가는 기능이다.
비선택 파일의 존재 확인과 실제 활성/Context 로딩 검증은 계속 별개로 유지한다.
