# 🎰 AutoLotto720

동행복권 연금복권720+ 자동 구매 & 당첨 확인 앱

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-blue.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-green.svg)](https://developer.android.com/jetpack/compose)
[![Platform](https://img.shields.io/badge/Platform-Android%2012+-green.svg)](https://developer.android.com)


<p align="center">
  <a href="https://autolotto720.umicorp.kr">
    <img src="docs/screenshots/landing.png" width="100%" />
  </a>
</p>

## ✨ 주요 기능

- **자동 구매** — 설정한 요일/시간에 매주 자동 구매 (1~5게임, 게임당 ₩1,000).
  슬롯별 모드(게임 A~E 각각 완전자동·반자동·수동 지정) 또는 **모든조 세트** 모드
  (1~5조 동일 자동번호 5매 일괄 구매)를 지원합니다
- **즉시 구매** — ⚡ 지금 바로 구매 / ➕ 같은 회차 추가 구매 (구매 확인 → 진행 → 결과 다이얼로그)
- **이중결제 방지 안전장치** — 예산 한도(1일/회차), 회차 중복 구매 가드, 결제 전 PENDING 선기록,
  결과 불명 시 재시도 금지. 지정번호가 이미 판매된 경우의 폴백 정책(재배정/조 유지/포기)도 선택 가능
- **당첨 확인** — 매주 목요일 19:05 추첨 이후 21:00 슬롯에서 자동 당첨 확인 & 푸시 알림
  (등수·당첨금 포함, 잔액 부족 알림 별도)
- **구매 기록** — 회차별 조/번호, 등수, 당첨금 한눈에 확인
- **보안 저장** — 계정 정보는 Android Keystore(EncryptedSharedPreferences) 암호화 저장
- **모던 디자인** — Material 3 Expressive 'Lucky Gloss' 룩 + 720 전용 'Neon Vault' 팔레트,
  네이티브 Jetpack Compose · 스프링 인터랙션
- **다국어** — 한국어 · English · 日本語
- **자동 업데이트** — 앱 내에서 새 버전 원탭 설치 (사이드로드 배포, 서명 검증 후 설치)

## 📱 스크린샷

<p align="center">
  <img src="docs/screenshots/home.jpg" width="200" />
  <img src="docs/screenshots/numbers.jpg" width="200" />
  <img src="docs/screenshots/history.jpg" width="200" />
  <img src="docs/screenshots/settings.jpg" width="200" />
</p>

## 🔧 빌드 방법

### 요구 사항

- Android SDK 36 (compileSdk / targetSdk 36), minSdk 31 (Android 12+)
- JDK 17
- Android Studio 또는 Gradle

### 빌드 & 설치

```bash
# 디버그 빌드 + 유닛 테스트
./gradlew :app:assembleDebug :app:testDebugUnitTest

# 릴리스 APK (서명: 루트 key.properties 필요)
./gradlew :app:assembleRelease
```

빌드된 APK: `app/build/outputs/apk/release/app-release.apk`
릴리스 서명은 루트 `key.properties`(gitignore)가 있을 때만 활성화됩니다. 이 포크(`umi-corp/autolotto720`)는
원본 autolotto와 별개의 애플리케이션 ID를 쓰므로 **자체 릴리스 키스토어**가 필요합니다 — 원본 keystore로는
서명/업데이트가 불가합니다.

## 📂 프로젝트 구조

주요 파일 발췌:

```
app/src/main/kotlin/com/umicorp/autolotto720/
├── MainActivity.kt              # 진입점 (Compose setContent)
├── AppContainer.kt              # 앱 스코프 컴포지션 루트 (상태·서비스·업데이트)
├── dhlottery/                   # 동행복권 역공학 세션
│   ├── AuthService.kt           # RSA 로그인 (도메인별 쿠키)
│   ├── PurchaseService720.kt    # 연금복권720+ 구매 (슬롯별·모든조 세트, AES 구매 계약)
│   ├── Crypto720.kt             # 구매 서브시스템 AES 암호화 (el JSESSIONID passphrase)
│   ├── ResultService720.kt      # 720 당첨번호 조회 (로그인 불필요)
│   ├── HistoryService720.kt     # 720 구매 내역 (마이페이지 원장)
│   ├── Round720.kt              # 회차 계산 (목 19:05 추첨 · 목 17~22시 판매정지 창)
│   ├── Feature720.kt            # PURCHASE_ENABLED 게이트
│   ├── DhlotterySession.kt      # OkHttp 세션 (수동 리다이렉트)
│   └── RsaCrypto.kt             # RSA PKCS1
├── data/
│   ├── SecureStore.kt           # EncryptedSharedPreferences (Flutter판 키 스키마 1:1 포트)
│   ├── BudgetGuard.kt           # 1일/회차 예산 한도 검사 (PENDING 금액 합산)
│   └── NumberConfig720.kt       # 슬롯 A~E · 모든조 세트 구매 설정 모델
├── scheduler/                   # AlarmManager 자가연쇄 + WorkManager
│   ├── AlarmScheduler.kt        # 알람 등록 (자동구매 1001 / 결과확인 1002)
│   ├── AutoPurchaseWorker.kt    # 백그라운드 자동 구매 (가드 체인 + 원장 기록)
│   ├── CheckResultWorker.kt     # 백그라운드 당첨 확인
│   ├── SchedulerReceivers.kt    # 부팅·앱 업데이트 시 알람 복원
│   └── Notifications.kt         # 알림 (구매/당첨/잔액)
├── update/
│   └── AppUpdater.kt            # 인앱 업데이트 (GitHub 릴리스 확인·서명 검증·설치)
└── ui/                          # Jetpack Compose · Material 3 Expressive
    ├── App.kt                   # 하단 pill 네비 + 4탭 페이저 (홈·번호·내역·설정)
    ├── SplashScreen.kt          # 스플래시 (자동 로그인)
    ├── screen/                  # Home · 번호 설정 · History · Settings
    ├── theme/                   # Lucky Gloss 테마 · Neon Vault 색 · 모션
    └── util/                    # 포맷 등 UI 헬퍼
```

## 🔐 보안

- 계정 정보는 **EncryptedSharedPreferences**(Android Keystore)로 암호화 저장
- 로그인 비밀번호는 동행복권 서버의 **RSA 공개키로 암호화** 후 전송, 구매 요청은 세션 키 기반 **AES 암호화** 계약 사용
- 인앱 업데이트는 다운로드한 APK의 **패키지명·서명 인증서를 설치본과 대조**한 뒤에만 설치
- 디버그 로그는 릴리즈 빌드에서 비활성화
- 모든 API 통신은 HTTPS

## ⚠️ 주의사항

- 이 앱은 **동행복권(dhlottery.co.kr) 계정**이 필요합니다
- 연금복권720+ 구매에는 **예치금 충전**이 선행되어야 합니다
- 판매 정지 시간대인 **목요일 17:00~22:00**(추첨 19:05 전후)에는 구매·예약 실행이 제한됩니다
- 예산 한도(1일/회차)를 초과하는 자동·즉시 구매는 결제 전에 차단됩니다
- 자동 구매가 정상 동작하려면 **배터리 최적화 제외** 설정을 권장합니다
- Google Play 도박 정책으로 인해 **스토어 배포 불가** → GitHub APK 직접 배포

## ⚖️ 면책 조항

동행복권의 공식 앱이 아니며, 사용에 따른 모든 책임은 사용자에게 있습니다. 동행복권 이용약관을 확인하고 본인의 판단 하에 사용하시기 바랍니다.

## 📄 라이선스

[MIT License](LICENSE) — 자유롭게 사용, 수정, 배포 가능합니다.
