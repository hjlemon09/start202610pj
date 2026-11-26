# CMS-ANALYZER 상세 알고리즘

- 작성 기준일: 2026-11-18
- 대상 프로젝트: `C:\Users\krist\git\start202610pj\javaTool\cms-analyzer`
- 전체 구성: [cms-analyzer-workflow.md](cms-analyzer-workflow.md)
- 아래 의사코드는 현재 Java 구현의 주요 분기와 자료구조를 설명한다. Java 문법을 그대로 복사한 실행 코드는 아니다.

## 1. 용어와 식별자

| 용어 | 의미 |
|---|---|
| 루트 컴포넌트 | 슬롯에 직접 연결된 컴포넌트 |
| PK | DB 객체 식별자. 컴포넌트 중복 조사 방지와 JSP 결과 연결에 사용 |
| UID | 사람이 확인할 수 있는 컴포넌트·페이지 식별값. PK를 대체하여 결합하지 않음 |
| 트리 행 ID | 특정 경로에 출현한 컴포넌트 한 행의 식별자 |
| 부모 트리 행 ID | 같은 트리에서 부모의 출현 행을 가리키는 식별자 |
| 현재 조상 집합 | 깊이 우선 탐색 중 현재 경로에 포함된 컴포넌트 PK 집합 |
| 조사 완료 집합 | 서버에서 참조 속성 조사를 완료한 컴포넌트 PK 집합 |

동일 PK라도 여러 경로에 출현할 수 있다. 참조 속성 조사는 PK별 한 번 수행하지만, 트리 출력은 경로별로 수행한다.

## 2. 전체 실행 제어 — execute.Execute

```text
ALGORITHM RunAnalyzer
    출력 경로 ← 현재 작업 디렉터리/output의 절대 경로
    출력 경로 표시

    아래 작업을 순서대로 실행:
        1. PageSlotComponentExporter.run(빈 인자 배열)
        2. RootComponentsExporter.run(빈 인자 배열)
        3. ComponentRelationsExporter.run(빈 인자 배열)
        4. ComponentTreeExporter.run(빈 인자 배열)
        5. PageComponentTreeExporter.run(빈 인자 배열)
        6. PageComponentTextTreeExporter.run(빈 인자 배열)

    각 작업 직전:
        단계 번호와 클래스명 표시

    작업이 InterruptedException을 발생시키면:
        현재 스레드의 인터럽트 상태 복구
        단계 정보를 포함한 예외 발생
        후속 작업 실행 중단

    그 밖의 예외가 발생하면:
        단계 정보를 포함한 예외 발생
        후속 작업 실행 중단

    모든 작업 성공 시:
        완료 메시지와 최종 CSV/TXT 경로 표시
END
```

Runner는 실행 인자를 각 단계로 전달하지 않는다. 전체 실행은 기본 `output`을 사용하며, Controller 검색 루트와 새 JSP URL은 JVM 시스템 속성으로 설정한다. `/ping`은 실행 순서에 포함되지 않는다.

## 3. 페이지·슬롯 수집

### 3.1 분석기의 HTTP 요청 — PageSlotComponentExporter

```text
ALGORITHM ExportPageSlots
    HTTP 클라이언트 생성
        HTTP/1.1 사용
        리다이렉트 자동 추적 안 함

    GET 요청 생성
        URL ← 클래스에 지정된 pages/all URL
        Host ← mmm-faec.local:9002
        Accept ← application/json

    요청 전송
    HTTP 상태가 200이 아니면 예외 발생

    응답 JSON → 문자열 맵의 리스트로 변환
    JSON 결과가 null이면 예외 발생

    정해진 19개 헤더 순서로 각 JSON 행을 CSV 행으로 변환
    null 값은 빈 문자열로 변환
    따옴표·쉼표·줄바꿈을 CSV 규칙에 맞추어 처리
    일부 수식 시작 문자는 선행 작은따옴표를 붙여 저장

    출력 폴더 생성
    page_slot_component.csv 저장
END
```

### 3.2 서버의 페이지 조회 — TestController.getAllPages

```text
ALGORITHM GetAllPages
    sql ← SQL_FILE을 UTF-8로 읽기
    시작에 BOM이 있으면 제거

    query ← FlexibleSearchQuery(sql)
    결과 타입 목록 ← RESULT_CLASSES의 19개 타입
    시작 위치 ← 0
    조회 개수 ← -1
    전체 건수 계산 ← false

    result ← FlexibleSearchService.search(query)
    response ← 빈 리스트

    result의 각 결과 행에 대해:
        item ← 순서 있는 맵
        COLUMNS의 각 위치 i에 대해:
            value ← 결과 행[i]
            item[COLUMNS[i]] ← null 또는 value.toString()
        response에 item 추가

    response를 JSON으로 반환
END
```

### 3.3 SQL의 데이터 구성

제공된 SQL은 다음 세 결과를 `UNION ALL`로 결합한다.

| 분기 | 조회 조건 및 연결 | 결과 표시 |
|---|---|---|
| 페이지 슬롯 | Online AbstractPage → ContentSlotForPage → ContentSlot → ElementsForSlot → 컴포넌트 | `PAGE`, `ACTIVE` |
| 템플릿 슬롯 | Online AbstractPage → PageTemplate → ContentSlotForTemplate → ContentSlot → 컴포넌트 | `TEMPLATE`, `ACTIVE` 또는 `OVERRIDDEN` |
| 슬롯 없는 페이지 | 페이지 슬롯과 템플릿 슬롯 관계가 모두 없는 Online 페이지 | `NONE`, `NO_SLOT` |

템플릿 슬롯과 같은 위치에 페이지 슬롯 관계가 있으면 `OVERRIDDEN`으로 표시한다. 이 행을 제거하지 않는다. 슬롯에서 컴포넌트로 이어지는 부분은 LEFT JOIN이므로 컴포넌트가 없는 슬롯도 수집될 수 있다. 페이지는 특정 카탈로그 ID로 한정하지 않고 `CatalogVersion.version = 'Online'` 조건을 사용한다.

SQL 결과는 페이지 타입, 페이지 ID, 슬롯 위치, 슬롯 상태, 컴포넌트 ID 순서로 정렬한다. 화면의 컴포넌트 배치 순서를 별도로 복원하는 코드는 없다.

**현재 파일의 불일치:** SQL 반환은 18개 컬럼이고 Java의 COLUMNS/RESULT_CLASSES는 `pageTemplatePk`를 포함한 19개다. 실제 사용 SQL은 Java와 컬럼 수·순서를 일치시켜야 한다. 다음 단계 설명은 정상적인 입력 CSV가 생성된 경우를 기준으로 한다.

## 4. 루트 컴포넌트 추출 — RootComponentsExporter

```text
ALGORITHM ExportRoots
    records ← page_slot_component.csv 파싱
    헤더가 없으면 예외 발생
    componentPk, componentId, componentName, componentType 위치 확인
    필수 헤더가 없으면 예외 발생

    roots ← 순서 있는 맵<PK, RootComponent>

    각 데이터 행에 대해:
        pk ← componentPk
        pk가 공백이면 건너뛰기

        roots에 pk가 없으면:
            roots[pk] ← PK, UID, 이름, 타입

    roots의 삽입 순서대로 root_components.csv 저장
END
```

같은 PK가 반복되면 첫 행의 정보를 사용한다. 페이지별·슬롯별 사용 위치는 원본 CSV에 유지하며 이 단계에서는 PK만 중복 제거한다.

## 5. 참조 관계 수집

### 5.1 분석기 — ComponentRelationsExporter

```text
ALGORITHM ExportRelations
    GET /cms-analyzer/components/navigation-relations 요청
    HTTP 상태가 200이 아니면 예외 발생
    응답 JSON → ComponentRelation 배열
    변환 결과가 null이면 예외 발생

    부모 PK, 자식 PK·UID·타입, 관계 종류,
    NavigationNode 경로·깊이, NavigationEntry 식별값을
    정해진 헤더 순서로 component_relations.csv에 저장
END
```

이 단계는 `root_components.csv`를 서버로 보내지 않는다. 서버가 `getAllPages()`를 다시 실행하여 시작점을 얻는다. 따라서 1단계와 3단계 사이 CMS 데이터가 변경되면 서로 다른 시점의 결과가 결합될 수 있다.

### 5.2 서버의 컴포넌트 조사 — TestController.getNavigationRelations

주요 자료구조는 다음과 같다.

| 자료구조 | 용도 |
|---|---|
| `pendingRoots` | 페이지 조회 결과에서 PK 중복을 제거한 시작점 |
| `pending` | 조사 대기 컴포넌트 PK 큐 |
| `scanned` | 이미 꺼내 조사한 컴포넌트 PK 집합 |
| `attributesByType` | 타입 코드별 조사 대상 속성명 캐시 |
| `response` | 관계 및 조사 완료 행 목록 |

```text
ALGORITHM GetNavigationRelations
    pendingRoots ← 빈 순서 있는 집합
    GetAllPages()의 각 행에 대해:
        componentPk가 있으면 pendingRoots에 PK 추가

    pending ← pendingRoots로 초기화한 큐
    scanned ← 빈 집합
    attributesByType ← 빈 맵
    response ← 빈 리스트

    pending이 빌 때까지:
        pk ← 큐의 맨 앞에서 꺼내기
        pk가 scanned에 있으면 건너뛰기
        scanned에 pk 추가

        parent ← ModelService.get(pk)
        typeCode ← parent.getItemtype()

        타입별 속성 캐시가 없으면:
            attributesByType[typeCode] ← ReferenceAttributes(typeCode)

        캐시된 각 속성명에 대해:
            value ← ModelService.getAttributeValue(parent, 속성명)
            value가 Iterable이면 각 원소에 CollectReference 적용
            아니면 value 자체에 CollectReference 적용

        response에 다음 조사 완료 행 추가:
            부모 = parent
            자식 = parent
            관계 종류 = SCAN_DB_REFERENCES

    response 반환
END
```

### 5.3 조사 대상 속성 결정 — referenceAttributes

```text
ALGORITHM ReferenceAttributes(typeCode)
    type ← TypeService로 typeCode의 ComposedType 조회
    componentBase ← AbstractCMSComponent 타입
    navigationBase ← CMSNavigationNode 타입

    type의 속성 정의를 가져와 다음 조건으로 필터:
        readable이 true
        qualifier가 owner, parents, currentCMSComponents 중 하나가 아님

        속성 타입이 CollectionType이면 요소 타입을 조사
        그 외에는 속성 타입 자체를 조사

        조사 타입이 ComposedType이며 다음 중 하나에 할당 가능:
            AbstractCMSComponent
            CMSNavigationNode

    qualifier 중복 제거
    qualifier 이름순 정렬
    속성명 목록 반환
END
```

단순 문자열에 포함된 UID, JSP의 include 구문, 임의의 Java 코드에서 조립하는 컴포넌트 참조는 이 알고리즘의 조사 대상이 아니다.

### 5.4 참조 대상 분기 — collectReference

```text
ALGORITHM CollectReference(parent, qualifier, target)
    target이 AbstractCMSComponentModel이면:
        부모 → target 관계 행 추가
        관계 종류 ← ATTRIBUTE:<qualifier>
        target.PK를 대기 큐 뒤에 추가

    그렇지 않고 target이 CMSNavigationNodeModel이면:
        CollectNavigationReferences(parent, qualifier, target) 실행

    그 밖의 값 또는 null:
        추가 처리 없음
END
```

### 5.5 Navigation 탐색 — collectNavigationReferences

NavigationNode 자체를 컴포넌트 트리의 별도 컴포넌트 노드로 만들지는 않는다. 원래 컴포넌트와 Entry 대상 컴포넌트 사이의 관계에 Navigation 경로 정보를 첨부한다.

```text
ALGORITHM CollectNavigationReferences(parent, qualifier, rootNode)
    stack ← 빈 스택
    push(rootNode, 깊이=0, 경로별 방문집합=빈 집합, 상위경로="")

    stack이 빌 때까지:
        frame ← pop()
        node ← frame.node
        nodePk ← node.PK

        nodePk가 frame의 방문집합에 있으면 건너뛰기
        visited ← frame의 방문집합 복사 후 nodePk 추가
        nodePath ← 상위경로와 nodePk를 '>'로 연결

        node의 각 NavigationEntry에 대해:
            entry.item이 CMS 컴포넌트가 아니면 건너뛰기
            child ← entry.item

            qualifier가 navigationNode이면:
                kind ← NAVIGATION_ENTRY
            아니면:
                kind ← NAVIGATION_ENTRY:<qualifier>

            parent → child 관계 행 생성
            nodePk, nodePath, frame.depth, Entry PK·UID 첨부
            response에 관계 행 추가
            pending 큐 뒤에 child.PK 추가

        node의 각 하위 노드에 대해:
            null이 아니면:
                push(하위 노드, frame.depth + 1, visited, nodePath)
END
```

방문집합은 경로별이므로 다른 경로에서 같은 노드를 참조하면 다시 나타날 수 있다. 하위 노드를 스택에 넣으므로 실제 방문 순서는 스택의 후입선출 규칙을 따른다.

## 6. 컴포넌트 트리 구성 — ComponentTreeExporter

### 6.1 관계 인덱스 생성

```text
roots ← root_components.csv의 루트 목록
names ← 루트 PK → 컴포넌트 이름
children ← 빈 맵<부모 PK, 관계 리스트>
scanned ← 빈 집합

component_relations.csv의 각 행에 대해:
    관계 종류가 SCAN_DB_REFERENCES이면:
        scanned에 부모 PK 추가
        실제 부모·자식 연결로 사용하지 않음
    그 외:
        children[부모 PK]에 자식 및 관계 메타데이터 추가

각 루트에 대해:
    Walk(루트, 깊이=0, 부모행ID="", 조상집합=빈 집합, PK경로=루트PK)
```

조사 완료 행의 부모·자식 PK가 같아도 자기 참조로 간주하지 않는다. 관계 CSV의 일반 관계 행을 별도로 중복 제거하는 처리는 없다.

### 6.2 깊이 우선 탐색과 순환 검사

```text
ALGORITHM Walk(rootPk, component, depth, parentPk, parentRowId, incoming, ancestors, path)
    ancestors에 component.PK 추가
    rowId ← 전체 트리 행 카운터 증가값
    outgoing ← children[component.PK] 또는 빈 목록
    status ← DetermineStatus(component, outgoing, ancestors)

    component_tree.csv에 현재 행 기록
        rootPk, rowId, parentRowId, depth, parentPk
        component의 PK·UID·타입
        루트 이름 맵에 있으면 이름, 없으면 빈 문자열
        path, status
        incoming의 관계·Navigation 메타데이터

    status가 NOT_SCANNED이면 warnings.csv에 경고 기록

    outgoing의 각 관계에 대해:
        child ← 관계의 자식
        child.PK가 ancestors에 있으면:
            warnings.csv에 CYCLE 기록
            해당 연결의 자식 행을 새로 만들지 않음
        그 외:
            Walk(rootPk, child, depth+1,
                 component.PK, rowId, 현재 관계,
                 ancestors, path + ">" + child.PK)

    ancestors에서 component.PK 제거
END
```

```text
ALGORITHM DetermineStatus(component, outgoing, ancestors)
    outgoing이 비었으면:
        component.PK가 scanned에 있으면 NO_DISCOVERED_CHILDREN
        아니면 NOT_SCANNED
    그 외:
        조상집합 밖의 자식이 하나도 없으면 CYCLE
        하나라도 있으면 BRANCH
END
```

`BRANCH`인 노드에도 일부 순환 연결이 있을 수 있다. 해당 연결은 `warnings.csv`에 남는다. `NOT_SCANNED` 판정은 현재 코드에서 자식 관계가 없는 경우에 수행된다.

예를 들어 `A → B → A`는 A와 B를 출력한 뒤 B에서 A로 되돌아가는 연결을 경고로 남긴다. `A → B → D`, `A → C → D`는 D를 두 경로에 각각 출력한다.

## 7. 페이지와 트리 결합 — PageComponentTreeExporter

```text
ALGORITHM JoinPagesAndTrees
    pages ← page_slot_component.csv의 필수 컬럼
    tree ← component_tree.csv의 필수 컬럼
    treeByRoot ← rootComponentPk별 트리 행 목록

    각 페이지·슬롯 데이터 행에 대해:
        pageSlotRowId ← 데이터 행 번호(1부터)
        rootPk ← componentPk
        matches ← treeByRoot[rootPk]

        rootPk가 비었으면:
            페이지·슬롯 정보 + NO_COMPONENT + 빈 트리 컬럼 출력

        그렇지 않고 matches가 없으면:
            페이지·슬롯 정보 + TREE_NOT_FOUND + 빈 트리 컬럼 출력

        matches가 있으면 각 트리 행에 대해:
            새 행 ID ← pageSlotRowId + ":" + 기존 treeRowId
            기존 부모행 ID가 있으면:
                새 부모행 ID ← pageSlotRowId + ":" + 기존 부모행 ID
            없으면 새 부모행 ID ← ""

            페이지·슬롯 정보 + MATCHED + 변환한 트리 정보 출력

    page_component_tree.csv 저장
END
```

이 접두어 변환으로 같은 루트 트리를 여러 슬롯에 배치해도 부모·자식 연결이 서로 섞이지 않는다. 결합된 트리 컬럼에는 `treeComponentPk`, `treeComponentId`, `treeComponentType`처럼 이름을 구분해 둔다.

## 8. 텍스트 트리 구성 — PageComponentTextTreeExporter

### 8.1 입력 검증 및 그룹화

```text
ALGORITHM BuildTextTree
    page_component_tree.csv를 UTF-8로 읽고 CSV 파싱
    필수 헤더 위치 확인
    pageTemplatePk가 있으면 추가로 사용
    rendering ← 새 ComponentRenderingResolver
    pages ← 순서 있는 맵<pagePk, Page>

    각 데이터 행에 대해:
        컬럼 개수가 헤더와 다르면 예외 발생
        slotSource와 slotStatus 양끝의 공백·일부 비표시 문자 제거
        slotSource가 PAGE, TEMPLATE, NONE 중 하나가 아니면 예외 발생
        pagePk가 비었으면 예외 발생

        page ← pages에서 조회하거나 생성
        슬롯 식별키 ← (slotSource, slotRelationPk, slotPk,
                       slotPosition, slotCatalogVersion)
        slot ← 해당 page에서 조회하거나 생성

        joinStatus에 따라:
            NO_COMPONENT:
                실제 슬롯이면 빈 슬롯 표시 예약
            TREE_NOT_FOUND:
                원본 componentPk·componentType으로 렌더링 정보 판정
                미결합 컴포넌트 노드 목록에 추가
            MATCHED:
                트리 컴포넌트 노드 생성
            그 외:
                예외 발생
END
```

페이지·슬롯·컴포넌트는 기본적으로 CSV에서 처음 만난 순서를 유지한다. 소스 검색과 렌더링 정보 조회는 TXT를 쓰기 전에 진행된다.

### 8.2 MATCHED 컴포넌트 노드 생성

```text
label ← Component UID + PK + 타입
nodeStatus가 비어 있지 않고 BRANCH가 아니면 상태 추가
navigationEntryPk가 있으면 Entry PK 추가
label 뒤에 rendering.describe(treeComponentType, treeComponentPk) 추가

componentDepth를 정수로 변환
깊이가 음수이거나 pageTreeRowId가 비었으면 예외 발생
슬롯 안에서 같은 pageTreeRowId가 이미 있으면 예외 발생

components[pageTreeRowId] ← (label, parentPageTreeRowId, componentDepth)
```

### 8.3 부모·자식 연결과 페이지 배치

```text
각 슬롯의 컴포넌트에 대해:
    depth=0이고 parentId가 비었으면 슬롯의 직접 자식으로 추가
    그 외:
        parent ← 같은 슬롯에서 parentId로 조회
        parent가 없거나 parent.depth+1 != 현재 depth이면 예외 발생
        parent의 자식으로 현재 노드 추가

슬롯 뒤에 미결합 컴포넌트 노드 추가
빈 실제 슬롯이면 [NO_COMPONENT] 컴포넌트 없음 추가

slotSource=TEMPLATE인 슬롯은 PageTemplate 아래에 배치
slotSource=PAGE 또는 NONE인 슬롯은 Page 아래에 배치
NONE 슬롯의 이름은 [NO_SLOT] 슬롯 없음
```

PageTemplate는 UID 또는 PK가 있을 때 생성한다. 템플릿 슬롯 행이 뒤늦게 등장하고 아직 템플릿 노드가 없으면 그 행의 정보를 사용하여 생성한다.

## 9. Controller·Renderer 판정 — ComponentRenderingResolver

### 9.1 Controller 소스 인덱스

```text
ALGORITHM IndexControllers
    sourceRoot ← cms.analyzer.sourceRoot 또는 C:/hybris/bin
    절대 경로로 정규화
    디렉터리가 아니면 예외 발생

    디렉터리를 재귀 탐색
    일반 파일만 선택
    파일명을 Locale.ROOT 기준 소문자로 변환했을 때
        controller.java로 끝나는 파일만 선택
    경로순 정렬

    controllers[소문자 파일명] ← 파일 경로 목록
    인덱스가 비었으면 예외 발생
END
```

인덱스 생성은 실행당 한 번이다. 소스가 없는 상태를 모든 타입의 기본 Controller 판정으로 처리하지 않는다. 기본 Controller 파일 자체가 존재하는지 검사하는 별도 분기는 없다.

### 9.2 타입별 분기

```text
ALGORITHM DetermineHandler(componentType)
    componentType이 RENDERERS 맵의 키이면:
        Controller=なし
        Renderer=맵에 등록된 설명

    그렇지 않고 componentType이 공백이면:
        Controller=[COMPONENT_TYPE_MISSING]

    그 외:
        key ← 소문자(componentType + "Controller.java")
        matches ← controllers[key] 또는 빈 목록

        0개이면:
            Controller=DefaultCMSComponentController
        1개이면:
            Controller=실제 파일명의 .java를 제거한 이름
            source=해당 경로
        여러 개이면:
            Controller=<componentType>Controller
            MULTIPLE_MATCHES=후보 경로 전체
END
```

Controller 파일명은 대소문자 구분 없이 비교한다. Renderer 맵의 타입 키와 `JspIncludeComponent` 타입 비교는 현재 코드에서 정확한 문자열 일치다.

| Renderer 맵의 키 | 출력할 설명 |
|---|---|
| `CMSParagraphComponent` | cmsParagraphComponentRenderer「直書き」 |
| `CMSLinkComponent` | cmsLinkComponentRenderer「直書き」 |
| `ImageMapComponent` | imageMapComponentRenderer「直書き」 |
| `AssistedServiceComponent` | AssistedServiceComponentRenderer 「cms/asm/assistedServiceComponent.jsp(条件付き)」 |
| `ProductAddToCartComponent` | B2BAcceleratorProductAddToCartComponentRenderer 「未確認」 |
| `ReorderAction` | DefaultAddOnCMSComponentRenderer 「未確認」 |
| `ApproveOrderAction` | DefaultAddOnCMSComponentRenderer 「未確認」 |

## 10. JspIncludeComponent의 JSP 조회

### 10.1 서버 — TestController.getJspIncludes

```text
ALGORITHM GetJspIncludes
    query ← 다음 FlexibleSearch
        SELECT {c:pk}, {c:uid}, {c:page}
        FROM {JspIncludeComponent AS c}

    결과 타입 ← PK, String, String
    시작 위치 ← 0
    조회 개수 ← -1
    전체 건수 계산 ← false
    query 실행

    각 결과 행에 대해:
        componentPk ← PK의 문자열
        componentUid ← UID 문자열 또는 null
        jspPath ← page 문자열 또는 null
        응답 목록에 추가

    JSON 배열 반환
END
```

서버 쿼리에는 특정 PK나 Online 카탈로그 조건이 없다. 실행 컨텍스트에서 조회되는 행을 일괄 반환하고, 분석기가 트리에 등장하는 PK와 연결한다. 예시 PK `8796102165564`를 고정 사용하지 않는다.

### 10.2 분석기 — fetchJspPaths 및 describe

```text
ALGORITHM DetermineJsp(componentType, componentPk)
    componentType != JspIncludeComponent이면:
        [jsp個別確認必要] 반환

    jspPaths 캐시가 아직 없으면:
        URL ← cms.analyzer.jspUrl 또는 기본 JSP API URL
        GET 요청
            HTTP/1.1, 리다이렉트 추적 안 함
            연결 타임아웃 30초, 요청 타임아웃 2분
            Host 및 Accept 헤더 설정

        요청 중 인터럽트가 발생하면:
            인터럽트 상태 복구 후 예외 발생
        HTTP 상태가 200이 아니면 예외 발생
        응답 JSON을 행 목록으로 변환
        변환 결과가 null이면 예외 발생

        paths ← 빈 맵
        각 행에 대해:
            행이 null이면 예외 발생
            componentPk가 없거나 공백이면 예외 발생
            jspPath 키가 없으면 예외 발생
            같은 PK가 이미 있으면 예외 발생

            jspPath 값이 null이면 빈 문자열로 바꾸어 저장
            paths[componentPk] ← jspPath

        jspPaths ← paths

    componentPk가 jspPaths에 없으면 [JSP_INCLUDE_NOT_FOUND]
    저장된 값이 공백이면 [JSP_PATH_EMPTY]
    그 외에는 저장된 경로 반환
END
```

같은 UID라도 PK가 다르면 다른 조회 결과를 사용한다. 동일 PK가 트리에 반복되어도 HTTP 요청을 반복하지 않는다. `page`의 값을 출력하며 실제 JSP 파일 존재 여부나 `getView()` 반환 경로는 조사하지 않는다.

## 11. 텍스트 작성과 파일 교체

```text
ALGORITHM SaveTextTree
    출력 폴더에 임시 파일 생성
    임시 파일을 UTF-8 Writer로 열기

    각 Page에 대해:
        Page.build()로 슬롯과 컴포넌트 연결 검증
        페이지 라벨 기록
        WriteBranches(Page, prefix="")
        빈 줄 기록

    Writer 닫기
    임시 파일을 page_component_tree.txt로 이동
        기존 파일이 있으면 교체

    finally:
        남아 있는 임시 파일 삭제
END

ALGORITHM WriteBranches(node, prefix)
    각 child에 대해:
        마지막 자식이면:
            prefix + "└── " + child.label 기록
            다음 prefix ← prefix + "    "
        그 외:
            prefix + "├── " + child.label 기록
            다음 prefix ← prefix + "│   "
        WriteBranches(child, 다음 prefix)
END
```

라벨의 줄바꿈과 탭은 한 줄 공백으로 바꾸어 트리 형식을 유지한다. 파일 교체에는 `REPLACE_EXISTING`을 사용하며 `ATOMIC_MOVE`는 지정하지 않는다. 따라서 조회·검증·임시 작성 단계의 오류에서 기존 TXT를 보존하는 구조이며, 파일시스템 장애까지 포함한 원자적 교체를 보장하는 것은 아니다.

## 12. 상태 및 오류 해석

| 표시 또는 오류 | 의미 / 처리 |
|---|---|
| `NO_SLOT` | 페이지와 템플릿에 슬롯 관계가 없음 |
| `NO_COMPONENT` | 슬롯에 컴포넌트 PK가 없음 |
| `TREE_NOT_FOUND` | 슬롯의 컴포넌트와 트리 루트 결합 실패 |
| `NO_DISCOVERED_CHILDREN` | 조사 완료, 발견된 하위 참조 없음 |
| `NOT_SCANNED` | 자식 관계가 없고 조사 완료 기록도 없음 |
| `CYCLE` | 현재 경로의 조상으로 되돌아가는 연결 |
| `MULTIPLE_MATCHES` | 같은 Controller 파일명 후보가 여러 경로에 존재 |
| `JSP_INCLUDE_NOT_FOUND` | JSP API 결과에 대상 PK가 없음 |
| `JSP_PATH_EMPTY` | 대상 행의 page 값이 null/공백 |
| API·JSON·CSV 오류 | 예외 전파 및 후속 단계 중단 |
| 부모 행 누락·깊이 불일치·중복 행 ID | TXT 생성 중단 |

`NO_DISCOVERED_CHILDREN`은 현재 조사 규칙으로 자식이 발견되지 않았다는 뜻이다. 런타임 화면 처리에서 어떤 방식으로도 다른 컴포넌트를 사용하지 않는다는 확정 판정은 아니다.

## 13. 실행 및 검증 범위

전체 실행 예시이며, 프로젝트 디렉터리에서 실행한다.

```powershell
javac -encoding UTF-8 -cp "lib/*" -d bin src/com/*.java
java "-Djdk.httpclient.allowRestrictedHeaders=host" "-Dcms.analyzer.sourceRoot=C:/hybris/bin" -cp "bin;lib/*" execute.Execute
```

환경에 맞는 기존 HTTPS truststore 옵션을 함께 적용해야 한다. 실제 사용을 위해서는 수정된 `TestController` 배포, 서버 SQL 경로 확인, 18/19개 컬럼 불일치 해소가 필요하다. 이 문서 작성 중 코드나 SQL을 추가 수정하지 않았다.

확인한 범위:

- 분석기 Java 소스 컴파일 성공
- 7개 Renderer 설명, 대소문자 무시 파일명 검색, 기본 Controller 판정
- 같은 파일명의 복수 Controller 후보 출력
- 중첩·반복 컴포넌트의 PK별 JSP 매칭
- 누락된 JSP 조회 행과 null 경로 표시
- `TREE_NOT_FOUND` 컴포넌트에 판정 결과 추가
- JSP API 오류 발생 시 기존 TXT 보존
- JspIncludeComponent가 없으면 새 JSP API를 호출하지 않음
- 존재하지 않거나 Controller Java 파일이 없는 소스 루트의 오류 처리

위 검증은 분석기와 로컬 테스트 서버 기준이다. Accelerator 의존 라이브러리를 사용한 TestController 빌드와 실제 DB 연동 전체 실행은 검증하지 않았다.
