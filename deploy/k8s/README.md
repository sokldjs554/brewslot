# Kubernetes 배포 (Kustomize)

```
deploy/k8s/
├── base/                     # 환경 무관 매니페스트
│   ├── infra/                # postgres · kafka · redis (로컬/데모용 — 운영은 RDS · MSK · ElastiCache)
│   └── apps/                 # 서비스 6개 (Kotlin 5 + FastAPI 1), order-service HPA · PDB
└── overlays/kind/            # kind(로컬 · CI): 이미지 태그 local, 개발용 Secret
```

| 항목 | 설정 | 이유 |
|---|---|---|
| 프로브 | startup / liveness / readiness 분리 (`/actuator/health/*`) | Flyway 마이그레이션 중에는 liveness 가 파드를 죽이지 않고, 준비 전에는 트래픽을 받지 않는다 |
| 롤링 업데이트 | `maxUnavailable: 0`, `preStop` 5초, `server.shutdown=graceful` | 교체 중 요청 유실 방지 |
| 확장 | order-service HPA (CPU 70%, 2~6개) · PDB `minAvailable: 1` | 출근 시간 주문 몰림. 만료 처리(SKIP LOCKED)와 Outbox 릴레이(advisory lock)는 다중 인스턴스에서 안전 |
| 알림 | notification-service 2개 | 인스턴스마다 모든 이벤트를 받는 브로드캐스트 소비라 SSE 가 어느 파드에 붙어도 알림을 받는다 |
| 보안 | non-root(uid 1001), 읽기 전용 루트 FS(`/tmp` 만 emptyDir), capabilities 전부 제거 | |
| 기동 순서 | `wait-for-deps` initContainer (앱 이미지의 bash 로 포트 확인) | 인프라보다 먼저 떠서 CrashLoop 백오프에 빠지지 않게 |
| 서비스 링크 | `enableServiceLinks: false` | 주입되는 `REDIS_PORT=tcp://…` · `KAFKA_PORT=…` 가 앱/브로커 설정을 덮어쓰는 문제 방지 |

## 실행

```bash
kind create cluster --name brewslot
# 이미지 빌드 · 적재는 .github/workflows/k8s.yml 과 같다
kubectl apply -k deploy/k8s/overlays/kind
scripts/k8s-smoke.sh
```

CI(`Kubernetes (kind)` 워크플로)가 main 에 push 될 때마다 실제 kind 클러스터에 배포하고 `scripts/k8s-smoke.sh` 로
**전체 Ready → 주문 → 결제 Saga → PAID → SSE 알림 수신 → DLT 콘솔 → order-service 롤링 재시작 후 재주문**까지 확인합니다.
