# ADR-0009. 모노레포 멀티 서비스와 한 JVM E2E

- 상태: 채택

## 결정

- 4개 서비스(order/payment/loyalty/settlement)는 **각자의 DB(database-per-service)** 와 컨슈머 그룹을 갖고,
  서로의 DB 를 절대 읽지 않는다. 통신은 Kafka 이벤트/명령과 HTTP(정산 → PG 파일) 뿐이다.
- 공유 모듈은 기술 공통(`libs/messaging`, `libs/web`, `libs/common`)과 **이벤트 계약**(Published Language)만 둔다.
  도메인 코드는 공유하지 않는다.
- 계약 호환 규칙: 소비자는 모르는 필드를 무시하고, 생산자는 필드 **추가만** 한다. 의미가 바뀌면 새 eventType.
  (서비스가 독립 배포되는 규모가 되면 Schema Registry + JSON Schema/Avro 로 이전)
- **E2E 테스트는 4개 서비스를 한 JVM 에서 각자의 ApplicationContext 로 띄운다.** 통신은 실제 Kafka(Testcontainers)로만 한다.
  Docker 이미지 4개를 빌드·기동하는 것보다 수십 배 빠르고, IDE 에서 브레이크포인트를 걸 수 있다.
