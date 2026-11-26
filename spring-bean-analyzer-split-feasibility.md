# spring-bean-analyzer 분리 구상의 실현 가능성 검토

> **2026-11-26 갱신**: `activated-extension-extractor`는 `extension-analyzer`로 이름을 바꾸고, 의존관계 분석과 extension 로드 순서 계산도 맡게 되었습니다.
> 4.2절과 6장의 "`load_order` 열 추가"는 `extension-analyzer`의 `extension-load-order.csv`(보장된 순서 쌍)로 구현했습니다. 순서가 보장되지 않는 쌍이 있어 하나의 순번 열 대신 쌍 목록으로 냈습니다.
> 본문은 검토 당시의 이름 그대로 둡니다.

## 검토 대상 구상

도구를 작고 단순한 프로그램으로 나누고, 결과 파일로 서로 연결해서 쓴다.

```text
activated-extension-extractor  (이미 sap-commerce-analyzer에서 분리됨)
        │ extensions.csv 등 (가장 최근 결과를 사용)
        ├──────────────────────────────┐
        ▼                              ▼
spring-bean-analyzer-from-xml   spring-bean-analyzer-from-source
        │                              │
        └──────────────┬───────────────┘
                       ▼
              spring-bean-analyzer (통합)
                       │
        ┌──────────────┴───────────────┐
        ▼                              ▼
  model-response 비교             staticAnalyzer
```

- `spring-bean-analyzer-from-xml`: XML을 기반으로 Spring 빈을 추정한다. sap-commerce-analyzer에 있는 기능을 분리한다.
- `spring-bean-analyzer-from-source`: Java 소스를 기반으로 Spring 빈을 추정한다.
- `spring-bean-analyzer`: 두 결과를 합쳐 더 완성도 있는 빈 정보를 만든다.
- 두 분석 도구는 activated-extension-extractor의 결과를 입력 옵션으로 사용한다.
- 통합 결과는 model-response 비교와 staticAnalyzer에서 사용한다.

---

## 1. 결론

**구상대로 구현할 수 있다.** 이미 있는 두 도구의 코드를 합치는 작업이 중심이라 처음부터 새로 만드는 부분은 적다.

다만 프로그램을 나누기 전에 다음 세 가지를 먼저 정해야 한다(4장).

1. 출력 스키마
2. 로드 순서와 컨텍스트 모델
3. 결과를 연결하는 규약

---

## 2. 현재 상태: Spring 빈 해석 코드가 두 벌 있다

| | sap-commerce-analyzer | staticAnalyzer (`CallTreeExtractor`) |
|---|---|---|
| XML 수집 | import 도달성, 로딩 진입점, JAR 내부 XML, `${}` 속성 해석까지 엄밀함 | `*-spring.xml` / `*-servlet.xml` 파일명 규칙으로 수집 |
| 컨텍스트 | CORE / WEB / SERVLET 구분 | core 1개 + web extension별 레지스트리(부모는 core) |
| 최종 승자 결정 | **하지 않음**. "후보"까지만 내고 override·profile·부모 컨텍스트는 미확정으로 둔다 (`docs/BEAN_REFERENCE_CANDIDATES.md`) | **함**. extension 로드 순서대로 덮어쓰기, alias, `ListMergeDirective`, 타입 기반 조회 |
| 출력 | CSV 21개 | 출력 없이 메모리에서만 사용 |

따라서 `spring-bean-analyzer-from-xml`의 실체는 **sap-commerce-analyzer의 엄밀한 수집에 staticAnalyzer의 최종 해석을 붙이는 것**이다.

- 둘 다 이미 동작하는 코드라 실현 가능성은 높다.
- 지금 두 구현이 따로 있다는 것 자체가 분리 작업의 좋은 동기다. 분리하면 해석 규칙이 한 곳으로 모인다.

---

## 3. 도구별 검토

### 3.1 spring-bean-analyzer-from-xml — 가능하고, 효과가 가장 크다

- `SpringXmlAnalyzer`와 `SpringLoadingAnalyzer`가 입력으로 받는 것은 다음 두 가지다.
  - `Map<String, ExtensionInfo>`: 이름, `extensioninfo.xml` 경로, 의존 목록
  - extension별 선택 상태(`selected()`, `status()`). `NOT_SELECTED` extension은 보조 검색에도 쓴다.
- 둘 다 extractor의 `extensions.csv`(선택된 것)와 `extension-activation.csv`(전체 판정)로 복원할 수 있다.
- **주의: extractor 결과를 "정확도를 높이는 선택 옵션"으로 두기는 어렵다.**
  - 결과가 없으면 비활성 extension의 빈까지 override 경쟁에 들어가므로 **최종 승자가 틀어진다.**
  - 사실상 필수 입력으로 두고, 없을 때는 "전체 extension 대상 · 부정확" 모드로 결과에 명확히 표시하는 편이 좋다.
- `${}` import 속성 해석(`ImportPropertyResolver`)은 config 폴더의 properties가 필요하므로 이 도구의 책임으로 함께 옮긴다.

### 3.2 spring-bean-analyzer-from-source — 가능하다. 다만 단독으로는 결론을 내지 못한다

소스에서 얻을 수 있는 것:

- `@Controller` / `@RestController` / `@Service` / `@Component` / `@Repository` 클래스와 기본 빈 이름(애너테이션 값, 없으면 단순 클래스명의 첫 글자를 소문자로)
- `@Configuration` 클래스의 `@Bean` 메서드
- 주입 지점: `@Resource(name)`, `@Autowired` + `@Qualifier`, 필드·setter·생성자
- 클래스 상속·구현 관계: 타입 기반 조회에 필요하다. 소스가 없는 클래스는 staticAnalyzer의 `.class` 읽기를 재사용한다.

단독으로 결론을 낼 수 없는 이유:

- 애너테이션이 붙은 클래스는 **XML의 `<context:component-scan base-package>` 범위 안에 있어야** 빈이 된다.
- 따라서 from-source는 "후보"만 내고, scan 범위 적용은 통합 도구가 하는 구조가 깔끔하다. from-source를 XML에 의존시키지 않아도 된다.

SAP Commerce는 빈 대부분이 XML로 정의된다. 그래서 from-source의 주된 수확은 다음과 같다.

- storefront·OCC 컨트롤러
- 주입 지점

### 3.3 spring-bean-analyzer (통합) — 가능하다

할 일:

- component-scan 범위를 적용해 애너테이션 빈 후보를 확정한다.
- 컨텍스트별 최종 빈을 확정한다.
  - XML과 scan의 등록 순서에 따른 override
  - alias
  - parent 빈 정의 상속(effective class, 속성)
  - 리스트 병합(`ListMergeDirective`, `modifyPopulatorList` 등)
- 주입 지점을 최종 빈에 연결한다.
- 확정하지 못한 것은 "미확정"으로 사유와 함께 남긴다.

---

## 4. 먼저 정해야 할 것

### 4.1 출력 스키마를 먼저 설계한다

통합 결과의 소비자가 필요로 하는 정보는 빈 목록보다 넓다.

| 소비자 | 필요한 정보 |
|---|---|
| staticAnalyzer | 빈 id·class뿐 아니라 property·list·lookup·constructor 인자·parent 체인 (예: converter → populators) |
| model-response 비교 | `ProductOption` → populator 맵, converter의 populator 목록, `fieldSetLevelMapping`의 문자열 값, Orika `fieldMapper` 설정 |

따라서 출력은 다음과 같은 **정규화된 표**가 되어야 한다. 외부 라이브러리 없이 CSV로 충분히 표현할 수 있다.

| 파일 (예시) | 행 단위 |
|---|---|
| `beans.csv` | 컨텍스트 × 최종 빈 (이름, alias, effective class, 정의 출처 XML/Java, scope, abstract, parent) |
| `bean-properties.csv` | 컨텍스트 × 빈 × 속성 항목 (종류: ref / value / list 항목 / map 항목, 인덱스·키, 값) |
| `bean-overrides.csv` | 같은 이름의 정의 중 밀려난 것과 그 이유 |
| `injection-points.csv` | 클래스 × 주입 지점 → 연결된 최종 빈 또는 미확정 사유 |

이 스키마를 from-xml보다 먼저 확정해야 세 도구가 맞물린다.

### 4.2 로드 순서와 컨텍스트 모델을 한 곳에서 정의한다

- override 승자는 **extension 로드 순서**로 정해진다. 지금은 staticAnalyzer가 이 순서를 따로 계산한다(`localextensions.xml` 순서 + 의존 대상 먼저).
- extractor의 `extensions.csv`에 **`load_order` 열을 추가**해 모든 도구가 같은 순서를 쓰게 하는 것을 권한다.
- 컨텍스트 모델(CORE / web extension별 WEB / SERVLET, 부모 관계)도 한 가지로 통일해야 한다. 지금은 두 도구가 서로 다르게 모델링한다.
- addon의 web spring XML이 storefront의 web 컨텍스트에 끼어드는 경로(`additionalWebSpringConfigs` 등)도 이 모델에 넣어야 한다. 이 부분은 대상 버전과 실제 설정에서 확인이 필요하다.

### 4.3 결과를 연결하는 규약을 정한다

- 모든 도구가 같은 형식을 따른다.
  - 결과는 `reports/<yyyyMMdd_HHmmss>_<id>/` 아래에 쓴다.
  - 그 안의 `summary.csv`에 도구 이름·버전과 **사용한 입력 결과 폴더**를 기록한다.
- 입력은 파일 또는 `reports` 폴더로 받는다. 폴더를 주면 가장 최근 결과를 고른다. 이 방식은 staticAnalyzer에 이미 구현되어 있다.
- 통합 도구는 from-xml과 from-source가 **같은 extractor 실행 결과**를 썼는지 검사한다.
  - 각각 "최신"을 고르다가 중간에 extractor를 다시 돌리면 입력이 어긋나기 때문이다.
- CSV 읽기·쓰기와 최신 폴더 선택 코드는 이미 프로젝트마다 중복되어 있다. 예를 들어 `ExtensionInfoParser`는 sap-commerce-analyzer와 extractor 두 곳에 있다.
  - 작은 공통 프로젝트로 묶을지, 프로젝트별 복사를 허용할지 정해야 한다.
  - 복사를 허용하면 각 도구는 완전히 독립적이지만, 규약이 바뀔 때 여러 곳을 고쳐야 한다.

---

## 5. 남는 한계와 검증 방법

정적 분석으로는 다음을 끝까지 재현하지 못한다. 결과에 "미확정"으로 남기는 설계가 필요하다.

- 활성 profile
- 실행 시 properties로 정해지는 `${}` (class 속성, 빈 이름 등)
- `@Conditional`
- `BeanDefinitionRegistryPostProcessor` 등 프로그램으로 등록하는 빈

정확도는 실제 서버와 비교해 측정할 수 있다.

- HAC 스크립팅 콘솔(Groovy)에서 core 컨텍스트의 빈 이름과 클래스를 덤프한다.
- 그 결과를 from-xml·통합 결과와 대조해 일치율과 불일치 원인을 기록한다.
- web 컨텍스트의 덤프 방법과 범위는 구현 단계에서 확인이 필요하다.

---

## 6. 권장 순서

1. activated-extension-extractor의 `extensions.csv`에 `load_order` 열 추가
2. 출력 스키마와 결과 연결 규약 확정 (4.1, 4.3)
3. `spring-bean-analyzer-from-xml`: sap-commerce-analyzer의 Spring 부분을 이식하고 staticAnalyzer의 최종 해석을 붙인다
4. `spring-bean-analyzer-from-source`
5. `spring-bean-analyzer` (통합)
6. staticAnalyzer가 내부 레지스트리 대신 통합 결과를 읽도록 전환. 기존 방식은 대체 경로로 유지한다
7. model-response 비교에서 populator·fields 정보 활용 ([accelerator-model-vs-occ-response-comparison.md](accelerator-model-vs-occ-response-comparison.md)의 3.3·3.4)
